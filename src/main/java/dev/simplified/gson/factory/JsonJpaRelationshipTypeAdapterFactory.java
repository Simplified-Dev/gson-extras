package dev.sbs.api.io.gson.factory;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.sbs.api.SimplifiedApi;
import dev.sbs.api.collection.concurrent.Concurrent;
import dev.sbs.api.persistence.json.JsonModel;
import dev.sbs.api.reflection.Reflection;
import dev.sbs.api.reflection.accessor.FieldAccessor;
import dev.sbs.api.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Optional;

/**
 * A Gson {@link TypeAdapterFactory} that automatically resolves JPA relationship fields
 * on {@link JsonModel} instances after deserialization.
 * <p>
 * After Gson deserializes a {@link JsonModel}, this factory inspects every declared field
 * (including inherited ones) for JPA relationship annotations and populates their transient
 * counterparts by querying the application's registered session repositories:
 * <ul>
 *   <li><b>{@code @ManyToOne @JoinColumn}</b>: reads the local FK field identified by
 *       {@link JoinColumn#name()} (converted to camelCase), then locates the matching entity
 *       in the referenced repository. {@link JoinColumn#referencedColumnName()} specifies the
 *       field on the referenced entity to match against; when omitted the {@link Id}-annotated
 *       field is used as the default.</li>
 *   <li><b>{@code @OneToMany}</b>: expects a sibling {@code *Ids} field on the same class
 *       (derived by singularizing the collection field name and appending {@code "Ids"}), then
 *       collects all repository entities whose {@link Id} value appears in that list.</li>
 * </ul>
 * <p>
 * This factory must be registered <em>before</em> {@link PostInitTypeAdapterFactory} in the
 * Gson factory chain so that relationship fields are fully populated before
 * {@link dev.sbs.api.io.gson.PostInit#postInit()} executes.
 */
@Slf4j
public class JsonJpaRelationshipTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> type) {
        if (!JsonModel.class.isAssignableFrom(type.getRawType()))
            return null;

        final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

        return new TypeAdapter<>() {

            @Override
            public void write(JsonWriter out, T value) throws IOException {
                delegate.write(out, value);
            }

            @Override
            public T read(JsonReader in) throws IOException {
                T obj = delegate.read(in);

                if (obj != null) {
                    try {
                        populateRelationships(obj);
                    } catch (Exception ex) {
                        log.debug("Exception during JPA relationship population for {}: {}", obj.getClass().getName(), ex.getMessage(), ex);
                    }
                }

                return obj;
            }

        };
    }

    /** Inspects all declared fields on {@code obj} and delegates JPA relationship population to the appropriate handler. */
    private void populateRelationships(@NotNull Object obj) {
        Class<?> objClass = obj.getClass();

        for (FieldAccessor<?> accessor : new Reflection<>(objClass).getFields()) {
            if (accessor.hasAnnotation(ManyToOne.class) && accessor.hasAnnotation(JoinColumn.class))
                this.populateManyToOne(obj, objClass, accessor);
            else if (accessor.hasAnnotation(OneToMany.class))
                this.populateOneToMany(obj, objClass, accessor);
        }
    }

    /**
     * Resolves a single {@code @ManyToOne @JoinColumn} field on {@code obj}.
     * <p>
     * The local FK value is read from the field named {@code toCamelCase(joinColumn.name())}.
     * The referenced entity is located by matching that value against the field identified by
     * {@link JoinColumn#referencedColumnName()} on the target type, falling back to the
     * {@link Id}-annotated field when {@code referencedColumnName} is empty.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void populateManyToOne(@NotNull Object obj, @NotNull Class<?> objClass, @NotNull FieldAccessor<?> accessor) {
        JoinColumn joinColumn = accessor.getAnnotation(JoinColumn.class).orElseThrow();
        String fkFieldName = StringUtil.toCamelCase(joinColumn.name());

        try {
            Field fkField = getFieldByName(objClass, fkFieldName);
            if (fkField == null) return;

            fkField.setAccessible(true);
            Object fkValue = fkField.get(obj);

            if (fkValue instanceof Optional<?> optValue) {
                if (optValue.isEmpty()) return;
                fkValue = optValue.get();
            }

            if (fkValue == null) return;

            Class<?> targetType = accessor.getHandle().getType();
            String referencedColName = joinColumn.referencedColumnName();
            Field refField = referencedColName.isEmpty()
                ? getIdField(targetType)
                : getFieldByColumnOrName(targetType, referencedColName);
            if (refField == null) return;
            refField.setAccessible(true);

            final Object finalFkValue = fkValue;
            final Field finalRefField = refField;

            Object found = SimplifiedApi.getSessionManager()
                .getRepository((Class) targetType)
                .matchFirstOrNull(entity -> {
                    try {
                        return finalFkValue.equals(finalRefField.get(entity));
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                });

            accessor.setAccessible(true);
            accessor.set(obj, found);
        } catch (Exception ex) {
            log.debug("Could not populate @ManyToOne field '{}' on {}: {}", accessor.getName(), objClass.getSimpleName(), ex.getMessage());
        }
    }

    /**
     * Resolves a {@code @OneToMany} collection field on {@code obj}.
     * <p>
     * The IDs field name is derived by singularizing the collection field name and appending
     * {@code "Ids"} (see {@link #toIdsFieldName(String)}). All repository entities whose
     * {@link Id} value is contained in that ID collection are then collected into the field.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void populateOneToMany(@NotNull Object obj, @NotNull Class<?> objClass, @NotNull FieldAccessor<?> accessor) {
        String idsFieldName = toIdsFieldName(accessor.getName());

        try {
            Field idsField = getFieldByName(objClass, idsFieldName);
            if (idsField == null) return;

            idsField.setAccessible(true);
            Object idsValue = idsField.get(obj);

            if (!(idsValue instanceof Collection<?> ids) || ids.isEmpty()) return;

            Type genericType = accessor.getHandle().getGenericType();
            if (!(genericType instanceof ParameterizedType pt)) return;

            Type elementTypeArg = pt.getActualTypeArguments()[0];
            if (!(elementTypeArg instanceof Class<?> elementClass)) return;

            var repo = SimplifiedApi.getSessionManager().getRepository((Class) elementClass);
            Field idField = getIdField(repo.getType());
            if (idField == null) return;
            idField.setAccessible(true);
            final Field finalIdField = idField;

            var result = repo.matchAll(entity -> {
                    try {
                        return ids.contains(finalIdField.get(entity));
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                })
                .collect(Concurrent.toUnmodifiableList());

            accessor.setAccessible(true);
            accessor.set(obj, result);
        } catch (Exception ex) {
            log.debug("Could not populate @OneToMany field '{}' on {}: {}", accessor.getName(), objClass.getSimpleName(), ex.getMessage());
        }
    }

    /**
     * Derives the IDs collection field name from the relationship field name by singularizing
     * and appending {@code "Ids"}. Examples: {@code categories} → {@code categoryIds},
     * {@code items} → {@code itemIds}, {@code mobTypes} → {@code mobTypeIds}.
     */
    private static @NotNull String toIdsFieldName(@NotNull String fieldName) {
        if (fieldName.endsWith("ies"))
            return fieldName.substring(0, fieldName.length() - 3) + "yIds";
        else if (fieldName.endsWith("s"))
            return fieldName.substring(0, fieldName.length() - 1) + "Ids";
        else
            return fieldName + "Ids";
    }

    /**
     * Finds a field on {@code clazz} (or a superclass) that matches {@code columnName}.
     * <p>
     * Precedence: a field annotated with {@code @Column(name = columnName)} takes priority;
     * otherwise the field whose Java name equals {@code toCamelCase(columnName)} is returned.
     */
    private static Field getFieldByColumnOrName(@NotNull Class<?> clazz, @NotNull String columnName) {
        String camelName = StringUtil.toCamelCase(columnName);
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                Column col = f.getAnnotation(Column.class);
                if (col != null && col.name().equals(columnName)) return f;
                if (f.getName().equals(camelName)) return f;
            }
            current = current.getSuperclass();
        }

        return null;
    }

    /** Finds a declared field by Java name on {@code clazz} or any superclass, stopping before {@link Object}. */
    private static Field getFieldByName(@NotNull Class<?> clazz, @NotNull String name) {
        return new Reflection<>(clazz)
            .getFields()
            .stream()
            .filter(accessor -> accessor.getName().equals(name))
            .map(FieldAccessor::getField)
            .findFirst()
            .orElse(null);
    }

    /** Finds the first field annotated with {@link Id} on {@code clazz} or any superclass, stopping before {@link Object}. */
    private static Field getIdField(@NotNull Class<?> clazz) {
        return new Reflection<>(clazz)
            .getFields()
            .stream()
            .filter(accessor -> accessor.hasAnnotation(Id.class))
            .map(FieldAccessor::getField)
            .findFirst()
            .orElse(null);
    }

}