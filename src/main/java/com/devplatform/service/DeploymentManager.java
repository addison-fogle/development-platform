package com.devplatform.service;

import com.devplatform.dto.DeploymentRequest;
import com.devplatform.exceptions.NotFoundException;
import com.devplatform.messaging.DeploymentEventPublisher;
import com.devplatform.model.Deployment;
import com.devplatform.model.DeploymentStatus;
import com.devplatform.model.Environment;
import com.devplatform.model.History;
import com.devplatform.model.Service;
import com.devplatform.repository.DeploymentRepository;
import com.devplatform.repository.EnvironmentRepository;
import com.devplatform.repository.ServiceRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class DeploymentManager {

    private static final Logger log = LoggerFactory.getLogger(DeploymentManager.class);

    private final DeploymentRepository deploymentRepository;
    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final HistoryManager historyManager;
    private final MeterRegistry meterRegistry;
    private final DeploymentEventPublisher eventPublisher;
    private final DeploymentPolicy deploymentPolicy;

    @Transactional(readOnly = true)
    public List<Deployment> getAll() {
        return deploymentRepository.findAll();
    }

    @Transactional
    public Deployment create(DeploymentRequest request, String idempotencyKey) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        Optional<Deployment> existing = findExistingDeployment(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            log.info("Found existing deployment: {} with idempotencyKey: {}",
                    existing.get().getId(), normalizedIdempotencyKey);
            return existing.get();
        }

        Service service = getRequiredService(request.serviceName());
        Environment environment = getRequiredEnvironment(request.environment());

        deploymentPolicy.validateCreate(request, service, environment);

        Deployment deployment = newPendingDeployment(
                service,
                environment,
                request.imageTag(),
                request.deployedBy(),
                normalizedIdempotencyKey);

        Deployment saved = saveHandlingIdempotencyConflict(deployment, normalizedIdempotencyKey);
        publishAfterCommit(() -> eventPublisher.publishCreated(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public Deployment getById(Long id) {
        return getRequiredDeployment(id);
    }

    @Transactional
    public Deployment rollback(Long id, String deployedBy, String idempotencyKey) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        Optional<Deployment> existing = findExistingDeployment(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            log.info("Found existing rollback deployment: {} with idempotencyKey: {}",
                    existing.get().getId(), normalizedIdempotencyKey);
            return existing.get();
        }

        Deployment target = getRequiredDeployment(id);
        Deployment previous = getPreviousSuccessfulDeployment(target);

        DeploymentRequest request = new DeploymentRequest(
                target.getService().getName(),
                target.getEnvironment().getName(),
                previous.getImageTag(),
                deployedBy);
        deploymentPolicy.validateCreate(request, target.getService(), target.getEnvironment());

        Deployment rollback = newPendingDeployment(
                target.getService(),
                target.getEnvironment(),
                previous.getImageTag(),
                deployedBy,
                normalizedIdempotencyKey);

        Deployment saved = saveHandlingIdempotencyConflict(rollback, normalizedIdempotencyKey);
        historyManager.record(saved, null, DeploymentStatus.PENDING);
        publishAfterCommit(() -> eventPublisher.publishCreated(saved));
        incrementRollbackCreated(target.getEnvironment());
        return saved;
    }

    @Transactional
    public Deployment updateStatus(Long id, DeploymentStatus status) {
        Deployment deployment = getRequiredDeployment(id);
        DeploymentStatus previous = deployment.getStatus();

        deployment.setStatus(status);

        if (status == DeploymentStatus.SUCCEEDED) {
            deploymentRepository.clearCurrent(deployment.getService(), deployment.getEnvironment());
            deployment.setCurrent(true);
        }

        Deployment saved = deploymentRepository.save(deployment);
        historyManager.record(saved, previous, status);

        incrementStatusTransition(previous, status);

        publishAfterCommit(() -> eventPublisher.publishStatusChanged(saved, previous));
        return saved;
    }

    public List<History> getHistory(Long deploymentId) {
        return historyManager.getByDeploymentId(deploymentId);
    }

    @Transactional
    public void delete(Long id) {
        Deployment deployment = getRequiredDeployment(id);
        historyManager.deleteByDeploymentId(deployment.getId());
        deploymentRepository.delete(deployment);
    }

    @Transactional(readOnly = true)
    public List<Deployment> getCurrentByEnvironment(String environmentName) {
        Environment environment = getRequiredEnvironment(environmentName);
        return deploymentRepository.findByEnvironmentAndCurrentTrue(environment);
    }

    private Optional<Deployment> findExistingDeployment(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return deploymentRepository.findByIdempotencyKey(idempotencyKey);
    }

    private Deployment getRequiredDeployment(Long id) {
        return deploymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Deployment not found: " + id));
    }

    private Service getRequiredService(String serviceName) {
        return serviceRepository.findByName(serviceName)
                .orElseThrow(() -> {
                    log.warn("Service name {} was not found", serviceName);
                    return new NotFoundException("Service was not found: " + serviceName);
                });
    }

    private Environment getRequiredEnvironment(String environmentName) {
        return environmentRepository.findByName(environmentName)
                .orElseThrow(() -> {
                    log.warn("Environment {} was not found", environmentName);
                    return new NotFoundException("Environment was not found: " + environmentName);
                });
    }

    private Deployment getPreviousSuccessfulDeployment(Deployment target) {
        return deploymentRepository
                .findTopByServiceAndEnvironmentAndStatusAndIdLessThanOrderByIdDesc(
                        target.getService(),
                        target.getEnvironment(),
                        DeploymentStatus.SUCCEEDED,
                        target.getId())
                .orElseThrow(() -> new NotFoundException(
                        "No previous successful deployment found for rollback: " + target.getId()));
    }

    private Deployment newPendingDeployment(
            Service service,
            Environment environment,
            String imageTag,
            String deployedBy,
            String idempotencyKey) {
        Deployment deployment = new Deployment();
        deployment.setService(service);
        deployment.setEnvironment(environment);
        deployment.setImageTag(imageTag);
        deployment.setDeployedBy(deployedBy);
        deployment.setStatus(DeploymentStatus.PENDING);
        deployment.setCurrent(false);
        deployment.setIdempotencyKey(idempotencyKey);
        return deployment;
    }

    private Deployment saveHandlingIdempotencyConflict(Deployment deployment, String idempotencyKey) {
        try {
            return deploymentRepository.saveAndFlush(deployment);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey == null) {
                throw ex;
            }
            return deploymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ex);
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (StringUtils.isBlank(idempotencyKey)) {
            return null;
        }
        return idempotencyKey.trim();
    }

    private void incrementRollbackCreated(Environment environment) {
        Counter.builder("deployments.rollback.created")
                .tag("environment", environment.getName())
                .register(meterRegistry)
                .increment();
    }

    private void incrementStatusTransition(DeploymentStatus previous, DeploymentStatus status) {
        Counter.builder("deployments.status.transitions")
                .tag("from", previous.name())
                .tag("to", status.name())
                .register(meterRegistry)
                .increment();
    }

    private void publishAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
