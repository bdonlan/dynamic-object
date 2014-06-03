package com.github.rschmitt.dynamicobject;

public interface DynamicObject<T extends DynamicObject<T>> {
    /**
     * @return the underlying Clojure map backing this instance. Downcasting the return value of this method to any
     * particular Java type (e.g. IPersistentMap) is not guaranteed to work with future versions of Clojure.
     */
    Object getMap();

    /**
     * @return the apparent type of this instance. Note that {@code getClass} will return the class of the interface
     * proxy and not the interface itself.
     */
    Class<T> getType();

    /**
     * Invokes clojure.pprint/pprint, which writes a pretty-printed representation of the object to the currently bound
     * value of *out*, which defaults to System.out (stdout).
     */
    void prettyPrint();

    /**
     * Like {@link DynamicObject#prettyPrint}, but returns the pretty-printed string instead of writing it to *out*.
     */
    String toFormattedString();

    /**
     * Serialize the given object to Edn. Any {@code EdnTranslator}s that have been registered through
     * {@link DynamicObject#registerType} will be invoked as needed.
     */
    public static <T extends DynamicObject<T>> String serialize(T o) {
        return DynamicObjects.serialize(o);
    }

    /**
     * Deserializes a DynamicObject from a String.
     *
     * @param edn  The Edn representation of the object.
     * @param type The type of class to deserialize. Must be an interface that extends DynamicObject.
     */
    public static <T extends DynamicObject<T>> T deserialize(String edn, Class<T> type) {
        return DynamicObjects.deserialize(edn, type);
    }

    /**
     * Use the supplied {@code map} to back an instance of {@code type}.
     */
    public static <T extends DynamicObject<T>> T wrap(Object map, Class<T> type) {
        return DynamicObjects.wrap(map, type);
    }

    /**
     * Create a "blank" instance of {@code type}, backed by an empty Clojure map. All fields will be null.
     */
    public static <T extends DynamicObject<T>> T newInstance(Class<T> type) {
        return DynamicObjects.newInstance(type);
    }

    /**
     * Register an {@link EdnTranslator} to enable instances of {@code type} to be serialized to and deserialized from
     * Edn using reader tags.
     */
    public static <T> void registerType(Class<T> type, EdnTranslator<T> translator) {
        DynamicObjects.registerType(type, translator);
    }

    /**
     * Deregister the given {@code translator}. After this method is invoked, it will no longer be possible to read or
     * write instances of {@code type} unless another translator is registered.
     */
    public static <T> void deregisterType(Class<T> type) {
        DynamicObjects.deregisterType(type);
    }

    /**
     * Register a reader tag for a DynamicObject type. This is useful for reading Edn representations of Clojure
     * records.
     */
    public static <T extends DynamicObject<T>> void registerTag(Class<T> type, String tag) {
        DynamicObjects.registerTag(type, tag);
    }

    /**
     * Deregister the reader tag for the given DynamicObject type.
     */
    public static <T extends DynamicObject<T>> void deregisterTag(Class<T> type) {
        DynamicObjects.deregisterTag(type);
    }
}
