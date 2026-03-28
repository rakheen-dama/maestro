/**
 * Spring MVC controllers for the Maestro admin dashboard.
 *
 * <p>{@link io.maestro.admin.controller.DashboardController} serves full Thymeleaf pages
 * for the overview, workflow list, detail, failed, signals, and timers views.
 *
 * <p>{@link io.maestro.admin.controller.AdminActionController} handles mutation actions
 * (retry, terminate, signal) that publish commands back to workflow services via Kafka.
 *
 * <p>{@link io.maestro.admin.controller.FragmentController} serves HTMX fragment responses
 * for partial page updates without full reloads.
 */
@NullMarked
package io.maestro.admin.controller;

import org.jspecify.annotations.NullMarked;
