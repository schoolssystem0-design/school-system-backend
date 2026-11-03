package com.selectcode.sms.shared.tenant;

import javax.sql.DataSource;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfig {

	@Bean
	static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)
						&& "dataSource".equals(beanName)) {
					return new TenantAwareDataSource(dataSource);
				}
				return bean;
			}
		};
	}

	@Bean
	HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
		return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
				new TenantIdentifierResolver());
	}
}
