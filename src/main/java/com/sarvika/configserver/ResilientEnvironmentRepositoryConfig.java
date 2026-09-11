package com.sarvika.configserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Our own AwsSecretsManagerEnvironmentRepository catches its own failures and degrades to
 * an empty contribution instead of blowing up the whole merged /{application}/{profile}
 * response - but Spring Cloud Config's built-in repositories (Git, Vault) don't do this:
 * if one of them throws (e.g. Vault is unreachable), findOne() propagates and the entire
 * request fails with a 500, even though the other (healthy) repositories could have
 * answered fine.
 *
 * This wraps every EnvironmentRepository bean in a dynamic proxy that intercepts only
 * findOne(...), catching a failure, logging it, and returning an empty Environment for
 * that one repository instead of propagating - while forwarding every other method
 * (including interfaces like SearchPathLocator that Git's repository also implements)
 * straight through untouched, so no other capability is lost.
 */
@Configuration
class ResilientEnvironmentRepositoryConfig {

	private static final Logger log = LoggerFactory.getLogger(ResilientEnvironmentRepositoryConfig.class);

	@Bean
	static BeanPostProcessor resilientEnvironmentRepositoryPostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (!(bean instanceof EnvironmentRepository) || Proxy.isProxyClass(bean.getClass())) {
					return bean;
				}
				Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(bean.getClass());
				return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces,
						new ResilientInvocationHandler(bean, beanName));
			}
		};
	}

	private static final class ResilientInvocationHandler implements InvocationHandler {

		private final Object delegate;
		private final String beanName;

		ResilientInvocationHandler(Object delegate, String beanName) {
			this.delegate = delegate;
			this.beanName = beanName;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (!isFindOne(method)) {
				return invokeDelegate(method, args);
			}

			String application = (String) args[0];
			String profile = (String) args[1];
			String label = (String) args[2];
			try {
				return invokeDelegate(method, args);
			}
			catch (RuntimeException e) {
				log.warn("Environment repository '{}' failed for application={}, profile={}, label={}; "
								+ "degrading to an empty contribution for this request rather than failing the "
								+ "whole merged response",
						beanName, application, profile, label, e);
				return new Environment(application, new String[] { profile }, label, null, null);
			}
		}

		private Object invokeDelegate(Method method, Object[] args) throws Throwable {
			try {
				return method.invoke(delegate, args);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		private static boolean isFindOne(Method method) {
			if (!method.getName().equals("findOne")) {
				return false;
			}
			Class<?>[] paramTypes = method.getParameterTypes();
			return paramTypes.length >= 3 && paramTypes[0] == String.class && paramTypes[1] == String.class
					&& paramTypes[2] == String.class;
		}
	}
}
