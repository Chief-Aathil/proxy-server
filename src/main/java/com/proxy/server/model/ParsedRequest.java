package com.proxy.server.model;

import java.util.Map;

public record ParsedRequest(String method, String path, Map<String, String> headers) {}

