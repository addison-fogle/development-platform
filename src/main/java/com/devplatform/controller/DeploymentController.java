package com.devplatform.controller;

import com.devplatform.dto.DeploymentRequest;
import com.devplatform.dto.RollbackRequest;
import com.devplatform.model.Deployment;
import com.devplatform.model.DeploymentStatus;
import com.devplatform.model.History;
import com.devplatform.service.DeploymentManager;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentController.class);

    private final DeploymentManager deploymentManager;

    @GetMapping
    public List<Deployment> getAll() {
        logger.info("Get all deployments endpoint hit.");
        return deploymentManager.getAll();
    }

    @GetMapping("/{id}")
    public Deployment getById(@PathVariable Long id) {
        logger.info("Get deployment endpoint hit with id: {} ", id);
        return deploymentManager.getById(id);
    }

    @GetMapping("/current")
    public List<Deployment> getCurrentByEnvironment(@RequestParam String environment) {
        logger.info("Get current deployment endpoint hit with environment: {} .", environment);
        return deploymentManager.getCurrentByEnvironment(environment);
    }

    @GetMapping("/{id}/history")
    public List<History> getHistory(@PathVariable Long id) {
        logger.info("Get deployment history endpoint hit with id: {}.", id);
        return deploymentManager.getHistory(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Deployment create(@Valid @RequestBody DeploymentRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        logger.info("Create deployment endpoint hit with request: {} and idempotencyKey: {}.", request.toString(), idempotencyKey);
        return deploymentManager.create(request, idempotencyKey);
    }

    @PostMapping("/{id}/rollback")
    @ResponseStatus(HttpStatus.CREATED)
    public Deployment rollback(@PathVariable Long id,
                               @RequestBody(required = false) RollbackRequest request,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String deployedBy = request == null ? "Unknown" : request.deployedBy();
        logger.info("Rollback deployment endpoint hit with id: {}, request: {}, and idempotencyKey: {}.",
                id, request != null ? request.toString() : "", idempotencyKey);
        return deploymentManager.rollback(id, deployedBy, idempotencyKey);
    }

    @PatchMapping("/{id}/status")
    public Deployment updateStatus(@PathVariable Long id, @RequestBody DeploymentStatus status) {
        logger.info("Update deployment status endpoint hit with id: {} and status: {}.", id, status);
        return deploymentManager.updateStatus(id, status);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        logger.info("Delete deployment endpoint hit with id: {}.", id);
        deploymentManager.delete(id);
    }
}
