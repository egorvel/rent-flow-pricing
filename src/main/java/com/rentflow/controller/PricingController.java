package com.rentflow.controller;

import java.net.URI;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rentflow.converter.PricingConverter;
import com.rentflow.dto.PricingDTO;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.service.PricingService;
import com.rentflow.service.PricingSortField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Validated
@Tag(name = "Pricing")
@RequestMapping(path = PricingController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class PricingController {

    public static final String PATH = "/api/v1/pricing";
    private static final Set<String> COLLECTION_PARAMETERS = Set.of("page", "size", "sort", "direction");

    private final PricingService service;
    private final PricingConverter converter;

    public PricingController(PricingService service, PricingConverter converter) {
        this.service = service;
        this.converter = converter;
    }

    @Operation(operationId = "createPricing", summary = "Create pricing")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Pricing created.",
                headers =
                        @Header(
                                name = HttpHeaders.LOCATION,
                                description = "Canonical path of the created pricing resource.",
                                schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = PricingDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed or the JSON body is malformed.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Pricing already exists for the serial number.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "The request media type is unsupported.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PricingDTO> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete pricing representation.",
                            required = true)
                    @Valid @RequestBody
                    PricingDTO request) {
        PricingDTO response = converter.toResponse(service.create(converter.toModel(request)));
        URI location = URI.create(PATH + "/" + response.serialNumber());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(operationId = "getPricing", summary = "Retrieve pricing")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Pricing found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = PricingDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "The serial-number path value is invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Pricing does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/{serialNumber}")
    public PricingDTO get(
            @Parameter(
                            description = "Case-sensitive equipment serial number.",
                            required = true,
                            schema = @Schema(minLength = 1, maxLength = 64, pattern = PricingDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = PricingDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber) {
        return converter.toResponse(service.get(serialNumber));
    }

    @Operation(operationId = "replacePricing", summary = "Fully replace pricing")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Pricing replaced.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = PricingDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed, serial numbers differ, or the JSON body is malformed.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Pricing does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "The request media type is unsupported.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PutMapping(path = "/{serialNumber}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PricingDTO replace(
            @Parameter(
                            description = "Case-sensitive equipment serial number.",
                            required = true,
                            schema = @Schema(minLength = 1, maxLength = 64, pattern = PricingDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = PricingDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete replacement with a matching serial number.",
                            required = true)
                    @Valid @RequestBody
                    PricingDTO request) {
        if (!serialNumber.equals(request.serialNumber())) {
            throw new RequestValidationException("serialNumber", "must match the path serial number");
        }
        return converter.toResponse(service.replace(converter.toModel(request)));
    }

    @Operation(operationId = "deletePricing", summary = "Permanently delete pricing")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Pricing permanently deleted."),
        @ApiResponse(
                responseCode = "400",
                description = "The serial-number path value is invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Pricing does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @DeleteMapping("/{serialNumber}")
    public ResponseEntity<Void> delete(
            @Parameter(
                            description = "Case-sensitive equipment serial number.",
                            required = true,
                            schema = @Schema(minLength = 1, maxLength = 64, pattern = PricingDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = PricingDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber) {
        service.delete(serialNumber);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listPricing", summary = "List and sort pricing")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bounded pricing page returned."),
        @ApiResponse(
                responseCode = "400",
                description = "One or more query parameters are invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    public PagedModel<PricingDTO> list(
            @Parameter(hidden = true) HttpServletRequest servletRequest,
            @Parameter(description = "Zero-based page number.", schema = @Schema(defaultValue = "0", minimum = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(value = 0, message = "must be at least 0") int page,
            @Parameter(
                            description = "Maximum resources returned per page.",
                            schema = @Schema(defaultValue = "20", minimum = "1", maximum = "100"))
                    @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "must be at least 1") @Max(value = 100, message = "must be at most 100") int size,
            @Parameter(
                            description = "Primary sort field.",
                            schema =
                                    @Schema(
                                            defaultValue = "serialNumber",
                                            allowableValues = {
                                                "serialNumber",
                                                "price",
                                                "weekendRate",
                                                "longRentalCondition",
                                                "longRentalDiscount",
                                                "deposit"
                                            }))
                    @RequestParam(defaultValue = "serialNumber")
                    String sort,
            @Parameter(
                            description = "Primary sort direction, parsed case-insensitively.",
                            schema =
                                    @Schema(
                                            defaultValue = "asc",
                                            allowableValues = {"asc", "desc"}))
                    @RequestParam(defaultValue = "asc")
                    String direction) {
        validateCollectionParameters(servletRequest);
        PricingSortField sortField = parseSortField(sort);
        Sort.Direction sortDirection = parseDirection(direction);
        Page<PricingDTO> result =
                service.list(page, size, sortField, sortDirection).map(converter::toResponse);
        return new PagedModel<>(result);
    }

    private void validateCollectionParameters(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!COLLECTION_PARAMETERS.contains(name)) {
                throw new RequestValidationException(name, "is not supported");
            }
            if (values.length != 1) {
                throw new RequestValidationException(name, "must be supplied exactly once");
            }
            if (values[0] == null || values[0].isBlank()) {
                throw new RequestValidationException(name, "must not be blank");
            }
        });
    }

    private PricingSortField parseSortField(String sort) {
        try {
            return PricingSortField.fromApiName(sort);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException(
                    "sort",
                    "must be one of serialNumber, price, weekendRate, longRentalCondition, longRentalDiscount, or deposit");
        }
    }

    private Sort.Direction parseDirection(String direction) {
        try {
            return Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("direction", "must be asc or desc");
        }
    }
}
