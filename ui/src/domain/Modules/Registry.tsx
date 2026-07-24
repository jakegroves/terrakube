import { Tabs, Button, Dropdown, Input, Space } from "antd";
import {
  SearchOutlined,
  CloudUploadOutlined,
  DownOutlined,
  AppstoreOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { queryOptions, useQuery } from "@tanstack/react-query";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ModuleList } from "./ModuleList";
import { ProviderList } from "../Providers/ProviderList";
import axiosInstance from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { FlatModule, FlatProvider } from "../types";
import { ErrorInformation } from "@/modules/api/types";
import type { MenuProps } from "antd";

type Params = {
  orgid: string;
};

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

// Lightweight fetch: only the fields the list views actually need
// Modules: ~1.5KB instead of ~73KB (98% smaller)
// Providers: ~200B instead of ~2KB
// Org name: ~100B instead of ~75KB

async function fetchModules(orgId: string): Promise<FlatModule[]> {
  const response = await axiosInstance.get(
    `organization/${orgId}/module?fields[module]=name,description,provider,latestVersion,downloadQuantity,createdDate,updatedDate`
  );
  return (response.data.data || []).map((m: any) => ({ id: m.id, ...m.attributes }));
}

async function fetchProviders(orgId: string): Promise<FlatProvider[]> {
  const response = await axiosInstance.get(`organization/${orgId}/provider?include=version`);
  const data = response.data.data || [];
  const included = response.data.included || [];

  // Build a map of providerId -> latest version number
  const providerVersions: Record<string, string[]> = {};
  for (const item of included) {
    if (item.type === "version") {
      const providerId = item.relationships?.provider?.data?.id;
      if (providerId) {
        if (!providerVersions[providerId]) providerVersions[providerId] = [];
        providerVersions[providerId].push(item.attributes.versionNumber);
      }
    }
  }

  return data.map((p: any) => {
    const versions = providerVersions[p.id] || [];
    // Sort semver descending to get latest
    versions.sort((a: string, b: string) => {
      const pa = a.split(".").map(Number);
      const pb = b.split(".").map(Number);
      for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const diff = (pb[i] || 0) - (pa[i] || 0);
        if (diff !== 0) return diff;
      }
      return 0;
    });
    return {
      id: p.id,
      ...p.attributes,
      latestVersion: versions[0] || undefined,
    };
  });
}

async function fetchOrgName(orgId: string): Promise<string> {
  const response = await axiosInstance.get(`organization/${orgId}?fields[organization]=name`);
  return response.data.data.attributes.name;
}

// Shared between the route loader (which primes the cache before navigation
// commits) and this component's own useQuery, so the two never fetch twice.
export const registryOrgNameQuery = (orgId: string) =>
  queryOptions({ queryKey: ["orgName", orgId], queryFn: () => fetchOrgName(orgId) });
export const registryModulesQuery = (orgId: string) =>
  queryOptions({ queryKey: ["registryModules", orgId], queryFn: () => fetchModules(orgId) });
export const registryProvidersQuery = (orgId: string) =>
  queryOptions({ queryKey: ["registryProviders", orgId], queryFn: () => fetchProviders(orgId) });

export const Registry = ({ setOrganizationName, organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchFilter, setSearchFilter] = useState("");

  const activeTab = searchParams.get("tab") || "modules";

  const orgNameQueryResult = useQuery(registryOrgNameQuery(orgid!));
  // Each tab's data is only fetched once it's the active tab (matching the
  // previous "lazy load on first switch" behavior); react-query then caches
  // it, so switching back and forth doesn't refetch.
  const modulesQuery = useQuery({
    ...registryModulesQuery(orgid!),
    enabled: Boolean(orgid) && activeTab === "modules",
  });
  const providersQuery = useQuery({
    ...registryProvidersQuery(orgid!),
    enabled: Boolean(orgid) && activeTab === "providers",
  });
  const modules = modulesQuery.data ?? [];
  const providers = providersQuery.data ?? [];

  const loading =
    orgNameQueryResult.isLoading || (activeTab === "providers" ? providersQuery.isLoading : modulesQuery.isLoading);
  const queryError = orgNameQueryResult.error || modulesQuery.error || providersQuery.error;
  const error: ErrorInformation | undefined = queryError ? { title: "Failed to load registry data" } : undefined;

  useEffect(() => {
    if (orgid) sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);
  }, [orgid]);

  useEffect(() => {
    if (orgNameQueryResult.data) {
      sessionStorage.setItem(ORGANIZATION_NAME, orgNameQueryResult.data);
      setOrganizationName(orgNameQueryResult.data);
    }
  }, [orgNameQueryResult.data, setOrganizationName]);

  const handleTabChange = (key: string) => {
    setSearchParams({ tab: key });
  };

  const handleSearchPublicRegistry = () => {
    navigate(`/organizations/${orgid}/registry/search`);
  };

  const publishMenuItems: MenuProps["items"] = [
    {
      key: "module",
      label: "Publish module",
      onClick: () => navigate(`/organizations/${orgid}/registry/create`),
    },
  ];

  const tabItems = [
    {
      key: "modules",
      label: (
        <span>
          <AppstoreOutlined style={{ marginRight: 8 }} />
          Modules
        </span>
      ),
      children: <ModuleList modules={modules} searchFilter={searchFilter} />,
    },
    {
      key: "providers",
      label: (
        <span>
          <CloudServerOutlined style={{ marginRight: 8 }} />
          Providers
        </span>
      ),
      children: <ProviderList providers={providers} searchFilter={searchFilter} />,
    },
  ];

  return (
    <PageWrapper
      title="Registry"
      subTitle={`Modules and providers in the ${organizationName} organization`}
      loadingText="Loading registry..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
      ]}
      fluid
      innerClassName="registry-centered"
      contentClassName="registry-centered"
      actions={
        <Space>
          <Button type="default" icon={<SearchOutlined />} onClick={handleSearchPublicRegistry}>
            Search public registry
          </Button>
          <Dropdown menu={{ items: publishMenuItems }} trigger={["click"]}>
            <Button type="primary" icon={<CloudUploadOutlined />}>
              Publish <DownOutlined />
            </Button>
          </Dropdown>
        </Space>
      }
    >
      <div>
        <Input
          placeholder="Filter providers and modules..."
          prefix={<SearchOutlined />}
          allowClear
          size="large"
          value={searchFilter}
          onChange={(e) => setSearchFilter(e.target.value)}
          style={{ width: "100%", maxWidth: 500, marginBottom: 24 }}
        />
        <Tabs activeKey={activeTab} onChange={handleTabChange} items={tabItems} size="large" />
      </div>
    </PageWrapper>
  );
};
