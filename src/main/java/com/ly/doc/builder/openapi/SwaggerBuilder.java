/*
 *
 * Copyright (C) 2018-2024 smart-doc
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.ly.doc.builder.openapi;

import com.ly.doc.constants.DocGlobalConstants;
import com.ly.doc.constants.MediaType;
import com.ly.doc.constants.ParamTypeConstants;
import com.ly.doc.model.*;
import com.ly.doc.utils.DocUtil;
import com.ly.doc.utils.OpenApiSchemaUtil;
import com.power.common.util.CollectionUtil;
import com.power.common.util.FileUtil;
import com.power.common.util.StringUtil;
import com.ly.doc.helper.JavaProjectBuilderHelper;
import com.ly.doc.model.openapi.OpenApiTag;
import com.ly.doc.utils.JsonUtil;
import com.thoughtworks.qdox.JavaProjectBuilder;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * @author xingzi Date 2022/9/17 15:16
 */
@SuppressWarnings("all")
public class SwaggerBuilder extends AbstractOpenApiBuilder {

	private static final SwaggerBuilder INSTANCE = new SwaggerBuilder();

	/**
	 * For unit testing
	 * @param config Configuration of smart-doc
	 */
	public static void buildOpenApi(ApiConfig config) {
		JavaProjectBuilder javaProjectBuilder = JavaProjectBuilderHelper.create();
		buildOpenApi(config, javaProjectBuilder);
	}

	/**
	 * Only for smart-doc maven plugin and gradle plugin.
	 * @param config Configuration of smart-doc
	 * @param projectBuilder JavaDocBuilder of QDox
	 */
	public static void buildOpenApi(ApiConfig config, JavaProjectBuilder projectBuilder) {
		ApiSchema<ApiDoc> apiSchema = INSTANCE.getOpenApiDocs(config, projectBuilder);
		INSTANCE.openApiCreate(config, apiSchema);
	}

	@Override
	String getModuleName() {
		return DocGlobalConstants.OPENAPI_2_COMPONENT_KRY;
	}

	/**
	 * Build OpenApi
	 * @param config Configuration of smart-doc
	 */
	@Override
	public void openApiCreate(ApiConfig config, ApiSchema<ApiDoc> apiSchema) {
		this.setComponentKey(getModuleName());
		Map<String, Object> json = new HashMap<>(8);
		json.put("swagger", "2.0");
		json.put("info", buildInfo(config));
		json.put("host", config.getServerUrl() == null ? "127.0.0.1" : config.getServerUrl());
		json.put("basePath", StringUtils.isNotBlank(config.getPathPrefix()) ? config.getPathPrefix() : "/");
		Set<OpenApiTag> tags = new HashSet<>();
		json.put("tags", tags);
		json.put("paths", buildPaths(config, apiSchema, tags));
		json.put("definitions", buildComponentsSchema(apiSchema));

		String filePath = config.getOutPath();
		filePath = filePath + DocGlobalConstants.OPEN_API_JSON;
		String data = JsonUtil.toPrettyJson(json);
		FileUtil.nioWriteFile(data, filePath);
	}

	/**
	 * Build openapi info
	 * @param apiConfig Configuration of smart-doc
	 */
	private static Map<String, Object> buildInfo(ApiConfig apiConfig) {
		Map<String, Object> infoMap = new HashMap<>(8);
		infoMap.put("title", apiConfig.getProjectName() == null ? "Project Name is Null." : apiConfig.getProjectName());
		infoMap.put("version", "1.0.0");
		return infoMap;
	}

	/**
	 * Build Servers
	 * @param config Configuration of smart-doc
	 */
	@Deprecated
	private static List<Map<String, String>> buildTags(ApiConfig config) {
		List<Map<String, String>> tagList = new ArrayList<>();
		Map<String, String> tagMap;
		List<ApiGroup> groups = config.getGroups();
		for (ApiGroup group : groups) {
			tagMap = new HashMap<>(4);
			tagMap.put("name", group.getName());
			tagMap.put("description", group.getApis());
			tagList.add(tagMap);
		}
		return tagList;
	}

	/**
	 * Build request
	 * @param apiConfig Configuration of smart-doc
	 * @param apiMethodDoc ApiMethodDoc
	 * @param apiDoc apiDoc
	 */
	public Map<String, Object> buildPathUrlsRequest(ApiConfig apiConfig, ApiMethodDoc apiMethodDoc, ApiDoc apiDoc,
			List<ApiExceptionStatus> apiExceptionStatuses) {
		Map<String, Object> request = new HashMap<>(20);
		request.put("summary", apiMethodDoc.getDesc());
		request.put("description", apiMethodDoc.getDetail());
		request.put("tags",
				Arrays.asList(apiDoc.getName(), apiDoc.getDesc(), apiMethodDoc.getGroup())
					.stream()
					.filter(StringUtil::isNotEmpty)
					.toArray(n -> new String[n]));
		List<Map<String, Object>> parameters = buildParameters(apiMethodDoc);
		// requestBody
		if (CollectionUtil.isNotEmpty(apiMethodDoc.getRequestParams())) {
			Map<String, Object> parameter = new HashMap<>();
			parameter.put("in", "body");
			parameter.putAll(buildContentBody(apiConfig, apiMethodDoc, false));
			parameters.add(parameter);
		}
		if (hasFile(parameters)) {
			List<String> formData = new ArrayList<>();
			formData.add(MediaType.MULTIPART_FORM_DATA);
			request.put("consumes", formData);
		}
		request.put("parameters", parameters);
		request.put("responses", buildResponses(apiConfig, apiMethodDoc, apiExceptionStatuses));
		request.put("deprecated", apiMethodDoc.isDeprecated());
		String operationId = apiMethodDoc.getUrl().replace(apiMethodDoc.getServerUrl(), "");
		// make sure operationId is unique and can be used as a method name
		request.put("operationId",
				apiMethodDoc.getName() + "_" + apiMethodDoc.getMethodId() + "UsingOn" + apiMethodDoc.getType());

		return request;
	}

	/**
	 * Check if the parameter contains a file
	 * @param parameters
	 * @return
	 */
	private boolean hasFile(List<Map<String, Object>> parameters) {
		for (Map<String, Object> param : parameters) {
			if (DocGlobalConstants.SWAGGER_FILE_TAG.equals(param.get("in"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * response body
	 * @param apiMethodDoc ApiMethodDoc
	 */
	@Override
	public Map<String, Object> buildResponsesBody(ApiConfig apiConfig, ApiMethodDoc apiMethodDoc) {
		Map<String, Object> responseBody = new HashMap<>(10);
		responseBody.put("description", "OK");
		if (CollectionUtil.isNotEmpty(apiMethodDoc.getResponseParams())
				|| Objects.nonNull(apiMethodDoc.getReturnSchema())) {
			responseBody.putAll(buildContentBody(apiConfig, apiMethodDoc, true));
		}
		return responseBody;
	}

	@Override
	List<Map<String, Object>> buildParameters(ApiMethodDoc apiMethodDoc) {
		{
			Map<String, Object> parameters;
			List<Map<String, Object>> parametersList = new ArrayList<>();
			// Handling path parameters
			for (ApiParam apiParam : apiMethodDoc.getPathParams()) {
				parameters = getStringParams(apiParam, false);
				parameters.put("type", DocUtil.javaTypeToOpenApiTypeConvert(apiParam.getType()));
				parameters.put("in", "path");
				parametersList.add(parameters);
			}
			for (ApiParam apiParam : apiMethodDoc.getQueryParams()) {
				if (apiParam.getType().equals(ParamTypeConstants.PARAM_TYPE_ARRAY) || apiParam.isHasItems()) {
					parameters = getStringParams(apiParam, false);
					parameters.put("type", ParamTypeConstants.PARAM_TYPE_ARRAY);
					parameters.put("items", getStringParams(apiParam, true));
					parametersList.add(parameters);
				}
				else {
					parameters = getStringParams(apiParam, false);
					parameters.put("type", DocUtil.javaTypeToOpenApiTypeConvert(apiParam.getType()));
					parametersList.add(parameters);
				}
			}
			// with headers
			if (!CollectionUtil.isEmpty(apiMethodDoc.getRequestHeaders())) {
				for (ApiReqParam header : apiMethodDoc.getRequestHeaders()) {
					parameters = new HashMap<>(20);
					parameters.put("name", header.getName());
					parameters.put("type", DocUtil.javaTypeToOpenApiTypeConvert(header.getType()));
					parameters.put("description", header.getDesc());
					parameters.put("required", header.isRequired());
					parameters.put("example", header.getValue());
					parameters.put("schema", buildParametersSchema(header));
					parameters.put("in", "header");
					parametersList.add(parameters);
				}
			}
			return parametersList;
		}
	}

	@Override
	Map<String, Object> getStringParams(ApiParam apiParam, boolean hasItems) {
		Map<String, Object> parameters;
		parameters = new HashMap<>(20);
		if (!hasItems) {
			if ("file".equalsIgnoreCase(apiParam.getType())) {
				parameters.put("in", DocGlobalConstants.SWAGGER_FILE_TAG);
			}
			else {
				parameters.put("in", "query");
			}
			parameters.put("name", apiParam.getField());
			parameters.put("description", apiParam.getDesc());
			parameters.put("required", apiParam.isRequired());
			parameters.put("type", apiParam.getType());
		}
		else {
			if (ParamTypeConstants.PARAM_TYPE_OBJECT.equals(apiParam.getType())
					|| (ParamTypeConstants.PARAM_TYPE_ARRAY.equals(apiParam.getType()) && apiParam.isHasItems())) {
				parameters.put("type", "object(complex POJO please use @RequestBody)");
			}
			else {
				String desc = apiParam.getDesc();
				if (desc.contains(ParamTypeConstants.PARAM_TYPE_FILE)) {
					parameters.put("type", ParamTypeConstants.PARAM_TYPE_FILE);
				}
				else if (desc.contains("string")) {
					parameters.put("type", "string");
				}
				else {
					parameters.put("type", "integer");
				}
			}
		}
		return parameters;
	}

	@Override
	public Map<String, Object> buildComponentsSchema(ApiSchema<ApiDoc> apiSchema) {
		return buildComponentData(apiSchema);
	}

}
