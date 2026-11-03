package com.selectcode.sms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

	static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.selectcode.sms");
	}

	@Test
	void moduleBoundariesAreRespected() {
		ApplicationModules.of(SmsApplication.class).verify();
	}

	@Test
	void noFloatingPointFields() {
		// Money must never be double/float (CLAUDE.md). No current use case needs floating point anywhere.
		fields().should()
			.notHaveRawType(double.class)
			.andShould()
			.notHaveRawType(float.class)
			.andShould()
			.notHaveRawType(Double.class)
			.andShould()
			.notHaveRawType(Float.class)
			.check(classes);
	}

	@Test
	void entitiesOutsidePlatformAreTenantScoped() {
		// Every school-owned entity must extend TenantScopedEntity; only the platform module owns global tables.
		com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
			.that()
			.areAnnotatedWith(Entity.class)
			.and()
			.resideOutsideOfPackage("com.selectcode.sms.platform..")
			.should()
			.beAssignableTo(TenantScopedEntity.class)
			.check(classes);
	}

	@Test
	void everyEndpointDeclaresItsAccessRule() {
		// SECURITY.md §2: deny by default; each handler states a permission, "any signed-in user" or "public".
		com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
			.that()
			.areDeclaredInClassesThat()
			.areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
			.and()
			.areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PutMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PatchMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)
			.should()
			.beAnnotatedWith(org.springframework.security.access.prepost.PreAuthorize.class)
			.orShould()
			.beAnnotatedWith(com.selectcode.sms.shared.security.AnyAuthenticated.class)
			.orShould()
			.beAnnotatedWith(com.selectcode.sms.shared.security.PublicEndpoint.class)
			.check(classes);
	}

	@Test
	void tenantContextIsNotChangedFromControllers() {
		noClasses().that()
			.resideInAPackage("..api..")
			.should()
			.callMethod(com.selectcode.sms.shared.tenant.TenantContext.class, "callAcrossAllSchools",
					java.util.function.Supplier.class)
			.check(classes);
	}

	@Test
	void apiTypesHaveUniqueNames() {
		// The OpenAPI document (and the generated frontend types) name schemas by simple class name; two API types with
		// the same name would silently merge into one schema.
		java.util.Map<String, java.util.Set<String>> byName = new java.util.TreeMap<>();
		java.util.Set<Class<?>> seen = new java.util.HashSet<>();
		for (var javaClass : classes) {
			Class<?> type = javaClass.reflect();
			if (type.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class)) {
				for (var method : type.getDeclaredMethods()) {
					collectApiTypes(method.getGenericReturnType(), seen);
					for (var parameter : method.getGenericParameterTypes()) {
						collectApiTypes(parameter, seen);
					}
				}
			}
		}
		seen.forEach(t -> byName.computeIfAbsent(t.getSimpleName(), k -> new java.util.TreeSet<>()).add(t.getName()));
		var duplicates = byName.entrySet().stream().filter(e -> e.getValue().size() > 1).toList();
		org.assertj.core.api.Assertions.assertThat(duplicates).as("API types sharing a simple name").isEmpty();
	}

	private static void collectApiTypes(java.lang.reflect.Type type, java.util.Set<Class<?>> seen) {
		if (type instanceof java.lang.reflect.ParameterizedType p) {
			collectApiTypes(p.getRawType(), seen);
			for (var argument : p.getActualTypeArguments()) {
				collectApiTypes(argument, seen);
			}
		}
		else if (type instanceof java.lang.reflect.GenericArrayType a) {
			collectApiTypes(a.getGenericComponentType(), seen);
		}
		else if (type instanceof java.lang.reflect.WildcardType w) {
			for (var bound : w.getUpperBounds()) {
				collectApiTypes(bound, seen);
			}
		}
		else if (type instanceof Class<?> c) {
			if (c.isArray()) {
				collectApiTypes(c.getComponentType(), seen);
				return;
			}
			if (!c.getName().startsWith("com.selectcode.sms.") || !seen.add(c)) {
				return;
			}
			if (c.isRecord()) {
				for (var component : c.getRecordComponents()) {
					collectApiTypes(component.getGenericType(), seen);
				}
			}
		}
	}
}
