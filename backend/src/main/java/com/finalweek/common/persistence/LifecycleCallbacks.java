package com.finalweek.common.persistence;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;

final class LifecycleCallbacks {
    private LifecycleCallbacks() {}

    static void invoke(Object entity, Class<? extends Annotation> annotation) {
        for (var method : entity.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(annotation)) continue;
            try {
                method.setAccessible(true);
                method.invoke(entity);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Persistence lifecycle callback failed", exception);
            }
        }
    }
}
