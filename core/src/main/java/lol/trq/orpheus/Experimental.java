/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks API that may change or disappear in any release, outside the semantic versioning promise. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
public @interface Experimental {}
