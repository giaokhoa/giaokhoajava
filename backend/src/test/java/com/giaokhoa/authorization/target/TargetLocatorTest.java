package com.giaokhoa.authorization.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.NativePolicyTemplates.TargetType;
import com.giaokhoa.authorization.target.TargetLocator.TargetMetadata;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TargetLocatorTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    @Test
    void locatesEveryTargetTypeWithTheTemplateKey() {
        assertEquals(
                NativePolicyTemplates.platformKey(),
                TargetLocator.locate(TargetType.PLATFORM, WORKSPACE, ACTOR, null, null)
                        .targetKey());
        assertEquals(
                NativePolicyTemplates.profileKey(ACTOR),
                TargetLocator.locate(TargetType.PROFILE, WORKSPACE, ACTOR, ACTOR, null)
                        .targetKey());
        assertEquals(
                NativePolicyTemplates.workspaceKey(WORKSPACE),
                TargetLocator.locate(TargetType.WORKSPACE, WORKSPACE, ACTOR, WORKSPACE, null)
                        .targetKey());
        assertEquals(
                NativePolicyTemplates.classKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.CLASS, WORKSPACE, ACTOR, ID, null).targetKey());
        assertEquals(
                NativePolicyTemplates.courseKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.COURSE, WORKSPACE, ACTOR, ID, null).targetKey());
        assertEquals(
                NativePolicyTemplates.courseSubjectKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.COURSE_SUBJECT, WORKSPACE, ACTOR, ID, null)
                        .targetKey());
        assertEquals(
                NativePolicyTemplates.resourceKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.RESOURCE, WORKSPACE, ACTOR, ID, null).targetKey());
        assertEquals(
                NativePolicyTemplates.resourceVersionKey(WORKSPACE, ID, VERSION),
                TargetLocator.locate(TargetType.RESOURCE_VERSION, WORKSPACE, ACTOR, ID, VERSION)
                        .targetKey());
        assertEquals(
                NativePolicyTemplates.activityKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.ACTIVITY, WORKSPACE, ACTOR, ID, null).targetKey());
        assertEquals(
                NativePolicyTemplates.resultKey(WORKSPACE, ID),
                TargetLocator.locate(TargetType.RESULT, WORKSPACE, ACTOR, ID, null).targetKey());
        assertEquals(
                NativePolicyTemplates.grantKey(ID),
                TargetLocator.locate(TargetType.GRANT, WORKSPACE, ACTOR, ID, null).targetKey());
    }

    @DisplayName("Metadata chỉ mang các phần định vị (location), không bao giờ mang nội dung")
    @Test
    void metadataCarriesLocationOnly() {
        TargetMetadata metadata =
                TargetLocator.locate(TargetType.RESOURCE_VERSION, WORKSPACE, ACTOR, ID, VERSION);
        assertEquals(TargetType.RESOURCE_VERSION, metadata.type());
        assertEquals(WORKSPACE, metadata.workspaceId());
        assertEquals(ID, metadata.objectId());
        assertEquals(VERSION, metadata.versionId());
    }

    @Test
    void parseRoundTripsEveryLocatedKey() {
        for (TargetMetadata located :
                new TargetMetadata[] {
                    TargetLocator.locate(TargetType.PLATFORM, WORKSPACE, ACTOR, null, null),
                    TargetLocator.locate(TargetType.PROFILE, WORKSPACE, ACTOR, ACTOR, null),
                    TargetLocator.locate(TargetType.WORKSPACE, WORKSPACE, ACTOR, WORKSPACE, null),
                    TargetLocator.locate(TargetType.CLASS, WORKSPACE, ACTOR, ID, null),
                    TargetLocator.locate(TargetType.COURSE_SUBJECT, WORKSPACE, ACTOR, ID, null),
                    TargetLocator.locate(
                            TargetType.RESOURCE_VERSION, WORKSPACE, ACTOR, ID, VERSION),
                    TargetLocator.locate(TargetType.GRANT, WORKSPACE, ACTOR, ID, null),
                }) {
            assertEquals(located, TargetLocator.parse(located.targetKey()).orElseThrow());
        }
    }

    @Test
    void parseRejectsKeysOutsideTheFixedTemplates() {
        assertTrue(TargetLocator.parse("workspace:not-a-uuid").isEmpty());
        assertTrue(TargetLocator.parse("tenant:" + WORKSPACE).isEmpty());
        assertTrue(TargetLocator.parse(null).isEmpty());
    }

    @Test
    void profileTargetMustBeTheActorAccount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(TargetType.PROFILE, WORKSPACE, ACTOR, ID, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(TargetType.PROFILE, WORKSPACE, null, ACTOR, null));
    }

    @DisplayName("Target thuộc workspace phải khớp workspace của route")
    @Test
    void workspaceTargetMustMatchRoute() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(TargetType.WORKSPACE, WORKSPACE, ACTOR, ID, null));
    }

    @Test
    void missingPartsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(TargetType.CLASS, null, ACTOR, ID, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(TargetType.CLASS, WORKSPACE, ACTOR, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TargetLocator.locate(
                                TargetType.RESOURCE_VERSION, WORKSPACE, ACTOR, ID, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TargetLocator.locate(null, WORKSPACE, ACTOR, ID, null));
    }
}
