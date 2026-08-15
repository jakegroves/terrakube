import { Tabs, Button, Dropdown, Input, Space } from "antd";
import {
  SearchOutlined,
  CloudUploadOutlined,
  DownOutlined,
  AppstoreOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ModuleList } from "./ModuleList";
import ModuleTable from "./components/ModuleTable";
import { ProviderList } from "../Providers/ProviderList";
import ProviderTable from "../Providers/components/ProviderTable";
import axiosInstance from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { FlatModule, FlatProvider } from "../types";
import { ErrorInformation } from "@/modules/api/types";
import ListViewToggle from "@/modules/layout/ListViewToggle/ListViewToggle";
import { getStoredListViewMode, ListViewMode } from "@/modules/layout/ListViewToggle/listViewPreference";
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

export const Registry = ({ setOrganizationName, organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchFilter, setSearchFilter] = useState("");
  const [listViewMode, setListViewMode] = useState<ListViewMode>(() => getStoredListViewMode());

  const activeTab = searchParams.get("tab") || "modules";

  // Each tab's data is fetched (and cached) the first time it's visited, so switching
  // tabs afterward doesn't refetch. visitedTabs replaces the old modulesLoaded/providersLoaded refs.
  const [visitedTabs, setVisitedTabs] = useState<Set<string>>(() => new Set([activeTab]));

  useEffect(() => {
    if (orgid) sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);
  }, [orgid]);

  const orgNameQuery = useQuery({
    queryKey: ["organizationName", orgid],
    queryFn: () => fetchOrgName(orgid!),
    enabled: Boolean(orgid),
  });

  useEffect(() => {
    if (orgNameQuery.data) {
      sessionStorage.setItem(ORGANIZATION_NAME, orgNameQuery.data);
      setOrganizationName(orgNameQuery.data);
    }
  }, [orgNameQuery.data, setOrganizationName]);

  const modulesQuery = useQuery({
    queryKey: ["modules", orgid],
    queryFn: () => fetchModules(orgid!),
    enabled: Boolean(orgid) && visitedTabs.has("modules"),
  });

  const providersQuery = useQuery({
    queryKey: ["providers", orgid],
    queryFn: () => fetchProviders(orgid!),
    enabled: Boolean(orgid) && visitedTabs.has("providers"),
  });

  const modules = modulesQuery.data ?? [];
  const providers = providersQuery.data ?? [];

  const activeTabQuery = activeTab === "providers" ? providersQuery : modulesQuery;
  const loading = orgNameQuery.isLoading || activeTabQuery.isLoading;
  const error: ErrorInformation | undefined = orgNameQuery.isError
    ? { title: "Failed to load registry data" }
    : activeTabQuery.isError
      ? { title: activeTab === "providers" ? "Failed to load providers" : "Failed to load modules" }
      : undefined;

  const handleTabChange = (key: string) => {
    setSearchParams({ tab: key });
    setVisitedTabs((prev) => (prev.has(key) ? prev : new Set(prev).add(key)));
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
      children:
        listViewMode === "compact" ? (
          <ModuleTable modules={modules} searchFilter={searchFilter} />
        ) : (
          <ModuleList modules={modules} searchFilter={searchFilter} />
        ),
    },
    {
      key: "providers",
      label: (
        <span>
          <CloudServerOutlined style={{ marginRight: 8 }} />
          Providers
        </span>
      ),
      children:
        listViewMode === "compact" ? (
          <ProviderTable providers={providers} searchFilter={searchFilter} />
        ) : (
          <ProviderList providers={providers} searchFilter={searchFilter} />
        ),
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
          <ListViewToggle value={listViewMode} onChange={setListViewMode} />
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
