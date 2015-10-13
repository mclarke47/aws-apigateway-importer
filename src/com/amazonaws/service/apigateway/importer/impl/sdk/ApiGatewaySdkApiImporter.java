package com.amazonaws.service.apigateway.importer.impl.sdk;

import com.amazonaws.services.apigateway.model.*;
import com.amazonaws.util.json.JSONArray;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createAddOperation;
import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createPatchDocument;
import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createReplaceOperation;
import static java.lang.String.format;

public class ApiGatewaySdkApiImporter {

    private static final Log LOG = LogFactory.getLog(ApiGatewaySdkApiImporter.class);

    @Inject
    protected ApiGateway apiGateway;

    public void deleteApi(String apiId) {
        deleteApi(apiGateway.getRestApiById(apiId));
    }

    public void deploy(String apiId, String deploymentStage) {
        LOG.info(String.format("Creating deployment for API %s and stage %s", apiId, deploymentStage));

        CreateDeploymentInput input = new CreateDeploymentInput();
        input.setStageName(deploymentStage);

        apiGateway.getRestApiById(apiId).createDeployment(input);
    }

    protected RestApi createApi(String name, String description) {
        LOG.info("Creating API with name " + name);

        CreateRestApiInput input = new CreateRestApiInput();
        input.setName(name);
        input.setDescription(description);

        return apiGateway.createRestApi(input);
    }

    protected void rollback(RestApi api) {
        deleteApi(api);
    }

    protected void deleteApi(RestApi api) {
        LOG.info("Deleting API " + api.getId());
        api.deleteRestApi();
    }

    protected Optional<Resource> getRootResource(RestApi api) {
        for (Resource r : buildResourceList(api)) {
            if ("/".equals(r.getPath())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    protected List<Resource> buildResourceList(RestApi api) {
        List<Resource> resourceList = new ArrayList<>();

        Resources resources = api.getResources();
        resourceList.addAll(resources.getItem());

        while (resources._isLinkAvailable("next")) {
            resources = resources.getNext();
            resourceList.addAll(resources.getItem());
        }

        return resourceList;
    }

    protected void deleteDefaultModels(RestApi api) {
        buildModelList(api).stream().forEach(model -> {
            LOG.info("Removing default model " + model.getName());
            try {
                model.deleteModel();
            } catch (Throwable ignored) {
            } // todo: temporary catch until API fix
        });
    }

    protected List<Model> buildModelList(RestApi api) {
        List<Model> modelList = new ArrayList<>();

        Models models = api.getModels();
        modelList.addAll(models.getItem());

        while (models._isLinkAvailable("next")) {
            models = models.getNext();
            modelList.addAll(models.getItem());
        }

        return modelList;
    }

    protected RestApi getApi(String id) {
        return apiGateway.getRestApiById(id);
    }

    protected void createModel(RestApi api, String modelName, String description, String schema, String modelContentType) {
        CreateModelInput input = new CreateModelInput();

        input.setName(modelName);
        input.setDescription(description);
        input.setContentType(modelContentType);
        input.setSchema(schema);

        api.createModel(input);
    }

    protected void cleanupModels(RestApi api, Set<String> models) {
        buildModelList(api).stream().filter(model -> !models.contains(model.getName())).forEach(model -> {
            LOG.info("Removing deleted model " + model.getName());
            try {
                model.deleteModel();
            } catch (Throwable ignored) {
            } // todo: temporary catch until API fix
        });
    }

    protected Optional<Resource> getResource(RestApi api, String parentResourceId, String pathPart) {
        for (Resource r : buildResourceList(api)) {
            if (pathEquals(pathPart, r.getPathPart()) && r.getParentId().equals(parentResourceId)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    protected boolean pathEquals(String p1, String p2) {
        return (StringUtils.isBlank(p1) && StringUtils.isBlank(p2)) || p1.equals(p2);
    }

    protected Optional<Resource> getResource(RestApi api, String fullPath) {
        for (Resource r : buildResourceList(api)) {
            if (r.getPath().equals(fullPath)) {
                return Optional.of(r);
            }
        }

        return Optional.empty();
    }

    protected Optional<Model> getModel(RestApi api, String modelName) {
        try {
            return Optional.of(api.getModelByName(modelName));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    protected void updateModel(RestApi api, String modelName, String schema) {
        api.getModelByName(modelName).updateModel(createPatchDocument(createReplaceOperation("/schema", schema)));
    }

    protected boolean methodExists(Resource resource, String httpMethod) {
        return resource.getResourceMethods().get(httpMethod.toUpperCase()) != null;
    }

    protected void deleteResource(Resource resource) {
        if (resource._isLinkAvailable("resource:delete")) {
            try {
                resource.deleteResource();
            } catch (NotFoundException error) {}
        }
        // can't delete root resource
    }

    /**
     * Build the full resource path, including base path, add any missing leading '/', remove any trailing '/',
     * and remove any double '/'
     * @param basePath the base path
     * @param resourcePath the resource path
     * @return the full path
     */
    protected String buildResourcePath(String basePath, String resourcePath) {
        if (basePath == null) {
            basePath = "";
        }
        String base = trimSlashes(basePath);
        if (!base.equals("")) {
            base = "/" + base;
        }
        String result = StringUtils.removeEnd(base + "/" + trimSlashes(resourcePath), "/");
        if (result.equals("")) {
            result = "/";
        }
        return result;
    }

    private String trimSlashes(String path) {
        return StringUtils.removeEnd(StringUtils.removeStart(path, "/"), "/");
    }

    protected Resource createResource(RestApi api, String parentResourceId, String part) {
        final Optional<Resource> existingResource = getResource(api, parentResourceId, part);

        // create resource if doesn't exist
        if (!existingResource.isPresent()) {
            LOG.info("Creating resource '" + part + "' on " + parentResourceId);

            CreateResourceInput input = new CreateResourceInput();
            input.setPathPart(part);
            Resource resource = api.getResourceById(parentResourceId);
            return resource.createResource(input);
        } else {
            return existingResource.get();
        }
    }

    protected String getStringValue(Object in) {
        return in == null ? null : String.valueOf(in);  // use null value instead of "null"
    }

    protected String getExpression(String area, String part, String type, String name) {
        return area + "." + part + "." + type + "." + name;
    }

    protected void updateMethod(RestApi api, Method method, String type, String name, boolean required) {
        String expression = getExpression("method", "request", type, name);
        Map<String, Boolean> requestParameters = method.getRequestParameters();
        Boolean requestParameter = requestParameters == null ? null : requestParameters.get(expression);

        if (requestParameter != null && requestParameter.equals(required)) {
            return;
        }

        LOG.info(format("Creating method parameter for api %s and method %s with name %s",
                api.getId(), method.getHttpMethod(), expression));

        method.updateMethod(createPatchDocument(createAddOperation("/requestParameters/" + expression, getStringValue(required))));
    }

    protected String escapeOperationString(String value) {
        return value.replaceAll("~", "~0").replaceAll("/", "~1");
    }

    protected String getAuthorizationTypeFromConfig(Resource resource, String method, JSONObject config) {
        if (config == null) {
            return "NONE";
        }

        try {
            return config.getJSONObject(resource.getPath())
                    .getJSONObject(method.toLowerCase())
                    .getJSONObject("auth")
                    .getString("type")
                    .toUpperCase();
        } catch (JSONException exception) {
            return "NONE";
        }
    }

}