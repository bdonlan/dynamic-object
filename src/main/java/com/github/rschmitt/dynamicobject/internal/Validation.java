package com.github.rschmitt.dynamicobject.internal;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.rschmitt.dynamicobject.DynamicObject;

class Validation {
    static <D extends DynamicObject<D>> void validateInstance(DynamicObjectInstance<D> instance) {
        MethodObject<D> methodObject = new MethodObject<>();
        methodObject.validate(instance, m -> {
            Object key = Reflection.getKeyForBuilder(m);
            return instance.getAndCacheValueFor(key, m.getGenericReturnType());
        });
        Collection<Method> missingFields = methodObject.missingFields;
        Map<Method, Class<?>> mismatchedFields = methodObject.mismatchedFields;
        if (!missingFields.isEmpty() || !mismatchedFields.isEmpty())
            throw new IllegalStateException(methodObject.getValidationErrorMessage());
    }

    static class MethodObject<D extends DynamicObject<D>> {
        private final Collection<Method> missingFields = new LinkedHashSet<>();
        private final Map<Method, Class<?>> mismatchedFields = new HashMap<>();
        private Function<Method, Object> getter;

        void validate(
                DynamicObjectInstance<D> instance,
                Function<Method, Object> getter
        ) {
            Collection<Method> fields = Reflection.fieldGetters(instance.getType());
            this.getter = getter;
            for (Method field : fields) {
                Object key = Reflection.getKeyForGetter(field);
                try {
                    validateField(field);
                } catch (ClassCastException | AssertionError cce) {
                    mismatchedFields.put(field, instance.getMap().get(key).getClass());
                }
            }
        }

        private void validateField(Method field) {
            Object val = getter.apply(field);
            if (Reflection.isRequired(field) && val == null)
                missingFields.add(field);
            if (val != null) {
                Type genericReturnType = field.getGenericReturnType();
                if (val instanceof Optional && ((Optional) val).isPresent()) {
                    genericReturnType = Reflection.getTypeArgument(genericReturnType, 0);
                    val = ((Optional) val).get();
                }
                Class<?> expectedType = Primitives.box(Reflection.getRawType(genericReturnType));
                Class<?> actualType = val.getClass();
                if (!expectedType.isAssignableFrom(actualType))
                    mismatchedFields.put(field, actualType);
                if (val instanceof DynamicObject)
                    ((DynamicObject) val).validate();
                else if (val instanceof List || val instanceof Set)
                    validateCollection((Collection<?>) val, genericReturnType);
                else if (val instanceof Map)
                    validateMap((Map<?, ?>) val, genericReturnType);
            }
        }

        @SuppressWarnings("unchecked")
        private void validateCollection(Collection<?> val, Type genericReturnType) {
            if (val == null) return;
            Class<?> baseCollectionType = Reflection.getRawType(genericReturnType);
            if (!baseCollectionType.isAssignableFrom(val.getClass()))
                throw new IllegalStateException(format("Wrong collection type: expected %s, got %s",
                        baseCollectionType.getSimpleName(), val.getClass().getSimpleName()));
            if (genericReturnType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
                List<Type> typeArgs = Arrays.asList(parameterizedType.getActualTypeArguments());
                assert typeArgs.size() == 1;

                Type typeArg = typeArgs.get(0);
                checkTypeVariable(typeArg);
                val.forEach(element -> checkElement(typeArg, element));
            }
        }

        private void checkTypeVariable(Type typeArg) {
            if (typeArg instanceof WildcardType)
                throw new UnsupportedOperationException("Wildcard return types are not supported");
            else if (typeArg instanceof ParameterizedType)
                return;
            else if (typeArg instanceof Class)
                return;
            else
                throw new UnsupportedOperationException("Unknown generic type argument type: " + typeArg.getClass().getCanonicalName());
        }

        private void checkElement(Type elementType, Object element) {
            if (elementType instanceof Class)
                checkAtomicElement((Class<?>) elementType, element);
            else
                checkNestedElement(elementType, element);
        }

        private void checkAtomicElement(Class<?> expectedType, Object element) {
            if (element != null) {
                Class<?> actualType = element.getClass();
                if (!expectedType.isAssignableFrom(actualType))
                    throw new IllegalStateException(format("Expected collection element of type %s, got %s",
                            expectedType.getCanonicalName(),
                            actualType.getCanonicalName()));
                if (element instanceof DynamicObject)
                    ((DynamicObject) element).validate();
            }
        }

        private void checkNestedElement(Type elementType, Object element) {
            Class<?> rawType = Reflection.getRawType(elementType);
            if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType))
                validateCollection((Collection<?>) element, elementType);
            else if (Map.class.isAssignableFrom(rawType))
                validateMap((Map<?, ?>) element, elementType);
            else
                throw new UnsupportedOperationException("Unsupported base type " + rawType.getCanonicalName());
        }

        private void validateMap(Map<?, ?> map, Type genericReturnType) {
            if (map == null) return;
            if (genericReturnType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
                List<Type> typeArgs = Arrays.asList(parameterizedType.getActualTypeArguments());
                assert typeArgs.size() == 2;

                typeArgs.forEach(this::checkTypeVariable);
                Type keyType = typeArgs.get(0);
                Type valType = typeArgs.get(1);

                map.keySet().forEach(k -> checkElement(keyType, k));
                map.values().forEach(v -> checkElement(valType, v));
            }
        }

        String getValidationErrorMessage() {
            StringBuilder ret = new StringBuilder();
            describeMissingFields(ret);
            describeMismatchedFields(ret);
            return ret.toString();
        }

        private void describeMismatchedFields(StringBuilder ret) {
            if (!mismatchedFields.isEmpty()) {
                ret.append("The following fields had the wrong type:\n");
                for (Map.Entry<Method, Class<?>> methodClassEntry : mismatchedFields.entrySet()) {
                    Method method = methodClassEntry.getKey();
                    String name = method.getName();
                    String expected = method.getReturnType().getSimpleName();
                    String actual = methodClassEntry.getValue().getSimpleName();
                    ret.append(format("\t%s (expected %s, got %s)%n", name, expected, actual));
                }
            }
        }

        private void describeMissingFields(StringBuilder ret) {
            if (!missingFields.isEmpty()) {
                ret.append("The following @Required fields were missing: ");
                List<String> fieldNames = missingFields.stream().map(Method::getName).collect(toList());
                ret.append(join(fieldNames));
                ret.append("\n");
            }
        }

        private static String join(List<String> strings) {
            return strings.stream().collect(Collectors.joining(", "));
        }
    }
}
