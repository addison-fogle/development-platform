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

        if (!StringUtils.isBlank(idempotencyKey )) {
            Optional<Deployment> existing = deploymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Found existing deployment: {} with idempotencyKey: {}",
                        existing.get().getId(), idempotencyKey);
                return existing.get();
            } else {
                String errorMessage = "No deployment found with existing idempotencyKey: " + idempotencyKey;
                log.warn(errorMessage);
                throw new NotFoundException(errorMessage);
            }
        }

        Optional<Service> service = serviceRepository.findByName(request.serviceName());
        if (service.isEmpty()) {
            log.warn("Service name {} was not found", request.serviceName());
            throw new NotFoundException("Service was not found: " + request.serviceName());
        }
        Optional<Environment> environment = environmentRepository.findByName(request.environment());
        if (environment.isEmpty()) {
            log.warn("Environment {} was not found", request.environment());
            throw new NotFoundException("Environment was not found: " + request.environment());
        }

        deploymentPolicy.validateCreate(request, service.get(), environment.get());

        Deployment deployment = new Deployment();
        deployment.setService(service.get());
        deployment.setEnvironment(environment.get());
        deployment.setImageTag(request.imageTag());
        deployment.setDeployedBy(request.deployedBy());
        deployment.setStatus(DeploymentStatus.PENDING);
        deployment.setCurrent(false);
        deployment.setIdempotencyKey(idempotencyKey);

        Deployment saved;
        try {
            saved = deploymentRepository.saveAndFlush(deployment);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                return deploymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> ex);
            }
            throw ex;
        }
        publishAfterCommit(() -> eventPublisher.publishCreated(saved));
        return saved;
    }

    public Deployment getById(Long id) {
        return deploymentRepository.getReferenceById(id);
    }
    @Transactional
    public Deployment rollback(Long id, String deployedBy, String idempotencyKey) {
        Deployment target = deploymentRepository.getReferenceById(id);

        if (!StringUtils.isBlank(idempotencyKey)) {
            Optional<Deployment> existing = deploymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }

        Deployment previous = deploymentRepository
                .findTopByServiceAndEnvironmentAndStatusAndIdLessThanOrderByIdDesc(
                        target.getService(),
                        target.getEnvironment(),
                        DeploymentStatus.SUCCEEDED,
                        target.getId())
                .orElseThrow(() -> new NotFoundException("No previous successful deployment found for rollback: " + id));

        DeploymentRequest request = new DeploymentRequest(
                target.getService().getName(),
                target.getEnvironment().getName(),
                previous.getImageTag(),
                deployedBy);
        deploymentPolicy.validateCreate(request, target.getService(), target.getEnvironment());

        Deployment rollback = new Deployment();
        rollback.setService(target.getService());
        rollback.setEnvironment(target.getEnvironment());
        rollback.setImageTag(previous.getImageTag());
        rollback.setDeployedBy(deployedBy);
        rollback.setStatus(DeploymentStatus.PENDING);
        rollback.setCurrent(false);
        rollback.setIdempotencyKey(idempotencyKey);

        Deployment saved;
        try {
            saved = deploymentRepository.saveAndFlush(rollback);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                return deploymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> ex);
            }
            throw ex;
        }

        historyManager.record(saved, null, DeploymentStatus.PENDING);
        publishAfterCommit(() -> eventPublisher.publishCreated(saved));
        Counter.builder("deployments.rollback.created")
                .tag("environment", target.getEnvironment().getName())
                .register(meterRegistry)
                .increment();
        return saved;
    }

    @Transactional
    public Deployment updateStatus(Long id, DeploymentStatus status) {
        Deployment deployment = deploymentRepository.getReferenceById(id);;
        DeploymentStatus previous = deployment.getStatus();

        deployment.setStatus(status);

        if (status == DeploymentStatus.SUCCEEDED) {
            deploymentRepository.clearCurrent(deployment.getService(), deployment.getEnvironment());
            deployment.setCurrent(true);
        }

        Deployment saved = deploymentRepository.save(deployment);
        historyManager.record(saved, previous, status);

        Counter.builder("deployments.status.transitions")
                .tag("from", previous.name())
                .tag("to", status.name())
                .register(meterRegistry)
                .increment();

        publishAfterCommit(() -> eventPublisher.publishStatusChanged(saved, previous));
        return saved;
    }

    public List<History> getHistory(Long deploymentId) {
        return historyManager.getByDeploymentId(deploymentId);
    }

    public void delete(Long id) {
        Deployment deployment = deploymentRepository.getReferenceById(id);
        historyManager.deleteByDeploymentId(deployment.getId());
        deploymentRepository.delete(deployment);
    }

    @Transactional(readOnly = true)
    public List<Deployment> getCurrentByEnvironment(String environmentName) {
        Environment environment = environmentRepository.findByName(environmentName)
                .orElseThrow(() -> new NotFoundException("Environment not found: " + environmentName));
        return deploymentRepository.findByEnvironmentAndCurrentTrue(environment);
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
