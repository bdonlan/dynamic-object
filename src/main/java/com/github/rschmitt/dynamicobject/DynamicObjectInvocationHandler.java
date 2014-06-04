package com.github.rschmitt.dynamicobject;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;

class DynamicObjectInvocationHandler<T extends DynamicObject<T>> implements InvocationHandler {
    private static final Object EMPTY_MAP = Clojure.read("{}");
    private static final IFn GET = Clojure.var("clojure.core", "get");
    private static final IFn ASSOC = Clojure.var("clojure.core", "assoc");
    private static final IFn CONJ = Clojure.var("clojure.core", "conj");
    private static final IFn META = Clojure.var("clojure.core", "meta");
    private static final IFn WITH_META = Clojure.var("clojure.core", "with-meta");
    private static final IFn NAME = Clojure.var("clojure.core", "name");
    private static final IFn PPRINT;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("clojure.pprint"));

        PPRINT = Clojure.var("clojure.pprint/pprint");
    }

    private final Object map;
    private final Class<T> type;
    private final Constructor<MethodHandles.Lookup> lookupConstructor;

    DynamicObjectInvocationHandler(Object map, Class<T> type, Constructor<MethodHandles.Lookup> lookupConstructor) {
        this.map = map;
        this.type = type;
        this.lookupConstructor = lookupConstructor;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        if (isBuilderMethod(method)) {
            if (isMetadataBuilder(method))
                return assocMeta(methodName, args[0]);
            Object val = maybeUpconvert(args[0]);
            val = unwrapCollectionElements(val, List.class, "[]");
            val = unwrapCollectionElements(val, Set.class, "#{}");
            return assoc(methodName, val);
        }

        if (method.isDefault())
            return invokeDefaultMethod(proxy, method, args);

        switch (methodName) {
            case "getMap":
                return map;
            case "getType":
                return type;
            case "toString":
                return map.toString();
            case "hashCode":
                return map.hashCode();
            case "prettyPrint":
                PPRINT.invoke(map);
                return null;
            case "toFormattedString":
                Writer w = new StringWriter();
                PPRINT.invoke(map, w);
                return w.toString();
            case "equals":
                Object other = args[0];
                if (other instanceof DynamicObject)
                    return map.equals(((DynamicObject) other).getMap());
                else
                    return method.invoke(map, args);
            default:
                if (isMetadataGetter(method))
                    return getMetadataFor(methodName);
                return getValueFor(method);
        }
    }

    private Object unwrapCollectionElements(Object val, Class<?> type, String empty) {
        if (val != null && type.isAssignableFrom(val.getClass())) {
            Iterable<?> iterable = (Iterable<?>) val;
            Object ret = Clojure.read(empty);
            for (Object o : iterable)
                if (o instanceof DynamicObject) {
                    DynamicObject<?> dynamicObject = (DynamicObject<?>) o;
                    Object map = dynamicObject.getMap();
                    map = withTypeMetadata(map, dynamicObject.getType());
                    assert META.invoke(map) != null;
                    ret = CONJ.invoke(ret, map);
                } else
                    ret = CONJ.invoke(ret, o);
            return ret;
        }
        return val;
    }

    private Object withTypeMetadata(Object obj, Class<?> type) {
        Object meta = META.invoke(obj);
        Object newMeta = ASSOC.invoke(meta, Clojure.read(":type"), Clojure.read(":" + type.getCanonicalName()));
        return WITH_META.invoke(obj, newMeta);
    }

    private Object maybeUpconvert(Object val) {
        if (val instanceof Float) val = Double.parseDouble(String.valueOf(val));
        else if (val instanceof Short) val = Long.valueOf((short) val);
        else if (val instanceof Byte) val = Long.valueOf((byte) val);
        else if (val instanceof Integer) val = Long.valueOf((int) val);
        return val;
    }

    private T assoc(String key, Object value) {
        Object keyword = Clojure.read(":" + key);
        if (value instanceof DynamicObject)
            value = ((DynamicObject) value).getMap();
        return DynamicObject.wrap(ASSOC.invoke(map, keyword, value), type);
    }

    private Object assocMeta(String key, Object value) {
        Object meta = META.invoke(map);
        if (meta == null)
            meta = EMPTY_MAP;
        meta = ASSOC.invoke(meta, key, value);
        return DynamicObject.wrap(WITH_META.invoke(map, meta), type);
    }

    private boolean isBuilderMethod(Method method) {
        return method.getReturnType().equals(type) && method.getParameterCount() == 1;
    }

    private boolean isMetadataBuilder(Method method) {
        if (method.getParameterCount() != 1)
            return false;
        for (Annotation[] annotations : method.getParameterAnnotations())
            for (Annotation annotation : annotations)
                if (annotation.annotationType().equals(Meta.class))
                    return true;
        return false;
    }

    private Object getMetadataFor(String key) {
        Object meta = META.invoke(map);
        Object val = GET.invoke(meta, key);
        return val;
    }

    private boolean isMetadataGetter(Method method) {
        if (method.getParameterCount() != 0)
            return false;
        for (Annotation annotation : method.getAnnotations())
            if (annotation.annotationType().equals(Meta.class))
                return true;
        return false;
    }

    private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?> declaringClass = method.getDeclaringClass();
        int TRUSTED = -1;
        return lookupConstructor.newInstance(declaringClass, TRUSTED)
                .unreflectSpecial(method, declaringClass)
                .bindTo(proxy)
                .invokeWithArguments(args);
    }

    @SuppressWarnings("unchecked")
    private Object getValueFor(Method method) {
        String methodName = method.getName();
        Object keywordKey = Clojure.read(":" + methodName);
        Object val = GET.invoke(map, keywordKey);
        if (val == null)
            val = getNonDefaultValue(method);
        if (val == null)
            return null;
        Class<?> returnType = method.getReturnType();
        if (returnType.equals(int.class) || returnType.equals(Integer.class)) return ((Long) val).intValue();
        if (returnType.equals(float.class) || returnType.equals(Float.class)) return ((Double) val).floatValue();
        if (returnType.equals(short.class) || returnType.equals(Short.class)) return ((Long) val).shortValue();
        if (returnType.equals(byte.class) || returnType.equals(Byte.class)) return ((Long) val).byteValue();
        if (DynamicObject.class.isAssignableFrom(returnType)) {
            Class<T> dynamicObjectType = (Class<T>) returnType;
            Object keyword = Clojure.read(":" + methodName);
            return DynamicObject.wrap(GET.invoke(map, keyword), dynamicObjectType);
        }
        if (Set.class.isAssignableFrom(returnType))
            return wrapElements((Set<Object>) val, new HashSet<>());
        if (List.class.isAssignableFrom(returnType))
            return wrapElements((List<Object>) val, new ArrayList<>());
        return val;
    }

    private Object wrapElements(Collection<Object> unwrappedSet, Collection<Object> ret) {
        for (Object elem : unwrappedSet) {
            Class<?> type = getTypeFromMetadata(elem);
            if (type == null)
                ret.add(elem);
            else
                ret.add(DynamicObject.wrap(elem, (Class<DynamicObject>) type));
        }
        return ret;
    }

    private Class<?> getTypeFromMetadata(Object obj) {
        String canonicalName = getTypeMetadata(obj);
        if (canonicalName == null) return null;
        try {
            return Class.forName(canonicalName);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String getTypeMetadata(Object obj) {
        Object metadata = META.invoke(obj);
        if (metadata == null) return null;
        Object typeMetadata = GET.invoke(metadata, Clojure.read(":type"));
        return (String) NAME.invoke(typeMetadata);
    }

    private Object getNonDefaultValue(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().equals(Key.class)) {
                String key = ((Key) annotation).value();
                if (key.charAt(0) != ':')
                    key = ":" + key;
                return GET.invoke(map, Clojure.read(key));
            }
        }
        return null;
    }
}
