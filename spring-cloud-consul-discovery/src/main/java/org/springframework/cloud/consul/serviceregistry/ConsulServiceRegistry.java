/*
 * Copyright 2013-2016 the original author or authors.
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

package org.springframework.cloud.consul.serviceregistry;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.cloud.consul.discovery.HeartbeatProperties;
import org.springframework.cloud.consul.discovery.TtlScheduler;

/**
 * @author Spencer Gibb
 */
@Slf4j
public class ConsulServiceRegistry implements ServiceRegistry<ConsulRegistration> {

	private ConsulClient client;

	private HeartbeatProperties ttlConfig;

	@Autowired(required = false)
	private TtlScheduler ttlScheduler;

	public ConsulServiceRegistry(ConsulClient client, HeartbeatProperties ttlConfig) {
		this.client = client;
		this.ttlConfig = ttlConfig;
	}

	@Override
	public void register(ConsulRegistration registration) {
		NewService service = registration.getService();
		log.info("Registering service with consul: {}", service.toString());
		client.agentServiceRegister(service, registration.getAclToken());
		if (ttlConfig.isEnabled() && ttlScheduler != null) {
			ttlScheduler.add(service);
		}
	}

	@Override
	public void deregister(ConsulRegistration registration) {
		String serviceId = registration.getService().getId();
		if (ttlScheduler != null) {
			ttlScheduler.remove(serviceId);
		}
		log.info("Deregistering service with consul: {}", serviceId);
		client.agentServiceDeregister(serviceId);
	}

	@Override
	public void close() {

	}
}
