package com.app.publishservice.config;

import java.util.HashMap;
import java.util.Map;

public class StoreApiProperties {

    private int defaultTimeoutSeconds = 60*10;
    private Map<String, StoreEndpointProperties> stores = new HashMap<>();

    /**
     * 获取Default 超时 Seconds。
     */
    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    /**
     * 设置Default 超时 Seconds。
     */
    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    /**
     * 获取商店。
     */
    public Map<String, StoreEndpointProperties> getStores() {
        return stores;
    }

    /**
     * 设置商店。
     */
    public void setStores(Map<String, StoreEndpointProperties> stores) {
        this.stores = stores;
    }

    /**
     * 获取商店。
     */
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

        /**
         * 判断是否Mock Enabled。
         */
        public boolean isMockEnabled() {
            return mockEnabled;
        }

        /**
         * 设置Mock Enabled。
         */
        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }

        /**
         * 判断是否Sandbox Enabled。
         */
        public boolean isSandboxEnabled() {
            return sandboxEnabled;
        }

        /**
         * 设置Sandbox Enabled。
         */
        public void setSandboxEnabled(boolean sandboxEnabled) {
            this.sandboxEnabled = sandboxEnabled;
        }

        /**
         * 获取Base URL。
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置Base URL。
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 获取令牌接口地址。
         */
        public String getTokenEndpoint() {
            return tokenEndpoint;
        }

        /**
         * 设置令牌接口地址。
         */
        public void setTokenEndpoint(String tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
        }

        /**
         * 获取提交接口地址。
         */
        public String getSubmitEndpoint() {
            return submitEndpoint;
        }

        /**
         * 设置提交接口地址。
         */
        public void setSubmitEndpoint(String submitEndpoint) {
            this.submitEndpoint = submitEndpoint;
        }

        /**
         * 获取状态接口地址。
         */
        public String getStatusEndpoint() {
            return statusEndpoint;
        }

        /**
         * 设置状态接口地址。
         */
        public void setStatusEndpoint(String statusEndpoint) {
            this.statusEndpoint = statusEndpoint;
        }
    }
}
