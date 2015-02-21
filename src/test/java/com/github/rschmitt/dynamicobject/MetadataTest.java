package com.github.rschmitt.dynamicobject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MetadataTest {
    private static final AnnotatedData AnnotatedData = DynamicObject.deserialize("{:value \"regular data\"}", AnnotatedData.class);

    @Test
    public void noInitialMetadata() {
        assertNull(AnnotatedData.source());
        assertEquals("{:value \"regular data\"}", DynamicObject.serialize(AnnotatedData));
    }

    @Test
    public void metadataBuilders() {
        AnnotatedData annotatedData = AnnotatedData.source("SQS");
        assertEquals("SQS", annotatedData.source());
    }

    @Test
    public void buildersWithCustomNames() {
        AnnotatedData annotatedData = AnnotatedData.withSource("SQS");
        assertEquals("SQS", annotatedData.source());
    }

    @Test
    public void customKeys() {
        CustomAnnotatedData annotatedData = DynamicObject.newInstance(CustomAnnotatedData.class);

        annotatedData = annotatedData.setSource("Azure");

        assertEquals("{}", DynamicObject.serialize(annotatedData));
        assertEquals("Azure", annotatedData.getSource());
    }

    @Test
    public void metadataIsNotSerialized() {
        AnnotatedData annotatedData = AnnotatedData.source("DynamoDB");
        assertEquals("{:value \"regular data\"}", DynamicObject.serialize(annotatedData));
    }

    @Test
    public void metadataIsIgnoredForEquality() {
        AnnotatedData withMetadata = AnnotatedData.source("Datomic");
        assertEquals(AnnotatedData, withMetadata);
    }
}

interface AnnotatedData extends DynamicObject<AnnotatedData> {
    @Meta String source();
    AnnotatedData source(String meta);
    @Meta @Key(":source") AnnotatedData withSource(String meta);
}

interface CustomAnnotatedData extends DynamicObject<CustomAnnotatedData> {
    @Meta @Key(":source") String getSource();
    @Meta @Key(":source") CustomAnnotatedData setSource(String source);
}
