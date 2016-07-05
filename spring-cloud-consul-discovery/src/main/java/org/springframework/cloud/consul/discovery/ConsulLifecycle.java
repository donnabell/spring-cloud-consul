/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.consul.discovery;

import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulServiceRegistry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.ecwid.consul.v1.agent.model.NewService;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Spencer Gibb
 */
@Slf4j
public class ConsulLifecycle extends AbstractAutoServiceRegistration<ConsulRegistration> {

	public static final char SEPARATOR = '-';

	private ConsulDiscoveryProperties properties;

	private HeartbeatProperties ttlConfig;

	@Autowired(required = false)
	private ServletContext servletContext;

	private NewService service = new NewService();
	private ConsulRegistration managementRegistration;
	private ConsulRegistration registration;

	public ConsulLifecycle(ConsulServiceRegistry serviceRegistry,
						   ConsulDiscoveryProperties properties,
						   HeartbeatProperties ttlConfig) {
		super(serviceRegistry);
		this.properties = properties;
		this.ttlConfig = ttlConfig;
	}

	@Override
	protected int getConfiguredPort() {
		return service.getPort() == null? 0 : service.getPort();
	}

	@Override
	protected void setConfiguredPort(int port) {
		service.setPort(port);
	}

	@Override
	@Retryable(interceptor = "consulRetryInterceptor")
	public void start() {
		super.start();
	}

	@Override
	protected ConsulRegistration getRegistration() {
		if (this.registration == null) {
			Assert.notNull(service.getPort(), "service.port has not been set");
			String appName = getAppName();
			service.setId(getServiceId());
			if (!properties.isPreferAgentAddress()) {
				service.setAddress(properties.getHostname());
			}
			service.setName(normalizeForDns(appName));
			service.setTags(createTags());

			// If an alternate external port is specified, register using it instead
			if (properties.getPort() != null) {
				service.setPort(properties.getPort());
			}

			if (this.properties.isRegisterHealthCheck()) {
				Integer checkPort;
				if (shouldRegisterManagement()) {
					checkPort = getManagementPort();
				} else {
					checkPort = service.getPort();
				}
				service.setCheck(createCheck(checkPort));
			}

			registration = new ConsulRegistration(service, this.properties.getAclToken());
		}
		return registration;
	}

	private NewService.Check createCheck(Integer port) {
		NewService.Check check = new NewService.Check();
		if (ttlConfig.isEnabled()) {
			check.setTtl(ttlConfig.getTtl());
			return check;
		}

		if (properties.getHealthCheckUrl() != null) {
			check.setHttp(properties.getHealthCheckUrl());
		} else {
			check.setHttp(String.format("%s://%s:%s%s", properties.getScheme(),
					properties.getHostname(), port,
					properties.getHealthCheckPath()));
		}
		check.setInterval(properties.getHealthCheckInterval());
		check.setTimeout(properties.getHealthCheckTimeout());
		return check;
	}

	public String getServiceId() {
		if (!StringUtils.hasText(properties.getInstanceId())) {
			return normalizeForDns(getContext().getId());
		} else {
			return normalizeForDns(properties.getInstanceId());
		}
	}

	@Override
	protected ConsulRegistration getManagementRegistration() {
		if (managementRegistration == null) {
			NewService management = new NewService();
			management.setId(getManagementServiceId());
			management.setAddress(properties.getHostname());
			management.setName(getManagementServiceName());
			management.setPort(getManagementPort());
			management.setTags(properties.getManagementTags());
			management.setCheck(createCheck(getManagementPort()));

			managementRegistration = new ConsulRegistration(management,
					this.properties.getAclToken());
		}
		return managementRegistration;
	}

	@Override
	protected Object getConfiguration() {
		return properties;
	}

	private List<String> createTags() {
		List<String> tags = new LinkedList<>(properties.getTags());
		if(servletContext != null
				&& StringUtils.hasText(servletContext.getContextPath())
				&& StringUtils.hasText(servletContext.getContextPath().replaceAll("/", ""))) {
			tags.add("contextPath=" + servletContext.getContextPath());
		}
		return tags;
	}

	@Override
	protected boolean isEnabled() {
		return this.properties.getLifecycle().isEnabled() && this.properties.isRegister();
	}
	
	@Override
	protected String getAppName() {
		String appName = properties.getServiceName();
		return StringUtils.isEmpty(appName) ? super.getAppName() : appName;
	}

	/**
	 * @return the serviceId of the Management Service
	 */
	public String getManagementServiceId() {
		return normalizeForDns(getContext().getId()) + SEPARATOR + properties.getManagementSuffix();
	}

	/**
	 * @return the service name of the Management Service
	 */
	public String getManagementServiceName() {
		return normalizeForDns(getAppName()) + SEPARATOR + properties.getManagementSuffix();
	}

	/**
	 * @return the port of the Management Service
	 */
	protected Integer getManagementPort() {
		// If an alternate external port is specified, use it instead
		if (properties.getManagementPort() != null) {
			return properties.getManagementPort();
		}
		return super.getManagementPort();
	}

	public static String normalizeForDns(String s) {
		if (s == null || !Character.isLetter(s.charAt(0))
				|| !Character.isLetterOrDigit(s.charAt(s.length()-1))) {
			throw new IllegalArgumentException("Consul service ids must not be empty, must start with a letter, end with a letter or digit, and have as interior characters only letters, digits, and hyphen");
		}

		StringBuilder normalized = new StringBuilder();
		Character prev = null;
		for (char curr : s.toCharArray()) {
			Character toAppend = null;
			if (Character.isLetterOrDigit(curr)) {
				toAppend = curr;
			} else if (prev == null || !(prev == SEPARATOR)) {
				toAppend = SEPARATOR;
			}
			if (toAppend != null) {
				normalized.append(toAppend);
				prev = toAppend;
			}
		}

		return normalized.toString();
	}
}
