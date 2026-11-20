package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission a tenant-scoped endpoint needs (tenancy design, section 5.2); the method then
 * receives the {@link Caller}. Every endpoint carries this or {@link NoPermissionRequired}: one with
 * neither is denied at runtime and fails the architecture test.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    Permission value();
}
