package com.app.publishservice.config;

import java.util.HashMap;
import java.util.Map;

public class StoreApiProperties {

    private int defaultTimeoutSeconds = 60*10;
    private Map<String, StoreEndpointProperties> stores = new HashMap<>();

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public Map<String, StoreEndpointProperties> getStores() {
        return stores;
    }

    public void setStores(Map<String, StoreEndpointProperties> stores) {
        this.stores = stores;
    }

    public StoreEndpointProperties getStore(String storeCode) {
        return stores.getOrDefault(storeCode, new StoreEndpointProperties());
    }

    public static class StoreEndpointProperties {
        private boolean mockEnabled = true;
        private boolean sandboxEnabled;
        private String baseUrl;
        private String tokenEndpoint;
        private String submitEndpoint;
        private String statusEndpoint;

        public boolean isMockEnabled() {
            return mockEnabled;
        }

        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }

        public boolean isSandboxEnabled() {
            return sandboxEnabled;
        }

        public void setSandboxEnabled(boolean sandboxEnabled) {
            this.sandboxEnabled = sandboxEnabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTokenEndpoint() {
            return tokenEndpoint;
        }

        public void setTokenEndpoint(String tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
        }

        public String getSubmitEndpoint() {
            return submitEndpoint;
        }

        public void setSubmitEndpoint(String submitEndpoint) {
            this.submitEndpoint = submitEndpoint;
        }

        public String getStatusEndpoint() {
            return statusEndpoint;
        }

        public void setStatusEndpoint(String statusEndpoint) {
            this.statusEndpoint = statusEndpoint;
        }
    }
}
