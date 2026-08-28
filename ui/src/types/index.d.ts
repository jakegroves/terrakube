declare global {
  interface Window {
    _env_: {
      REACT_APP_AUTHORITY: string;
      REACT_APP_CLIENT_ID: string;
      REACT_APP_REDIRECT_URI: string;
      REACT_APP_BASE_PATH?: string;
      REACT_APP_SCOPE: string;
      REACT_APP_TERRAKUBE_API_URL: string;
      REACT_APP_TERRAKUBE_SEND_COOKIES?: string;
      REACT_APP_TERRAKUBE_VERSION: string;
      REACT_APP_REGISTRY_URI: string;
      REACT_APP_OTEL_ENABLED?: string;
      REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT?: string;
      REACT_APP_OTEL_TRACES_SAMPLE_RATE?: string;
      REACT_APP_OTEL_SERVICE_NAME?: string;
    };
  }
}

export {};
