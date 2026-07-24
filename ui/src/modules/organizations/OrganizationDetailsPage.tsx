import { Button, Flex, List, Space } from "antd";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ImportOutlined, PlusOutlined } from "@ant-design/icons";
import { useEffect, useMemo, useState } from "react";
import { queryOptions, useQuery } from "@tanstack/react-query";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { Link, useNavigate, useParams } from "react-router-dom";
import workspaceService from "@/modules/workspaces/workspaceService";
import { ErrorInformation } from "@/modules/api/types";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { TagModel } from "./types";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";
import {
  getStoredWorkspaceSortOption,
  setStoredWorkspaceSortOption,
  sortWorkspaces,
  WorkspaceSortOption,
} from "@/modules/workspaces/utils/workspaceSort";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

// Shared between the route loader (which primes the cache before navigation
// commits) and this component's own useQuery, so the two never fetch twice.
export const organizationWorkspacesQuery = (orgId: string) =>
  queryOptions({
    queryKey: ["organizationWorkspaces", orgId],
    queryFn: async () => {
      const response = await workspaceService.listWorkspaces(orgId);
      if (response.isError) {
        const errorInfo: ErrorInformation = {
          title: response.error?.status || "Operation failed",
          message: response.error?.message || "Failed due to an unknown error",
        };
        throw errorInfo;
      }
      return response.data!;
    },
  });

export default function OrganizationsDetailPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [filteredWorkspaces, setFilteredWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [sortOption, setSortOption] = useState<WorkspaceSortOption>(() => getStoredWorkspaceSortOption());
  const [tags, setTags] = useState<TagModel[]>([]);

  const { data, isLoading: loading, error: queryError } = useQuery(organizationWorkspacesQuery(id!));
  const workspaces = useMemo(() => data?.workspaces ?? [], [data]);
  const error = queryError as ErrorInformation | undefined;

  const sortedWorkspaces = useMemo(
    () => sortWorkspaces(filteredWorkspaces, sortOption),
    [filteredWorkspaces, sortOption]
  );

  const projects = useMemo(() => {
    const seen = new Set<string>();
    return workspaces
      .filter((ws) => ws.projectId && !seen.has(ws.projectId) && seen.add(ws.projectId!))
      .map((ws) => ({ id: ws.projectId!, name: ws.projectName! }));
  }, [workspaces]);

  const handleSortChange = (option: WorkspaceSortOption) => {
    setSortOption(option);
    setStoredWorkspaceSortOption(option);
  };

  useEffect(() => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, id!);
  }, [id]);

  useEffect(() => {
    setFilteredWorkspaces(workspaces);
  }, [workspaces]);

  useEffect(() => {
    if (data) {
      sessionStorage.setItem(ORGANIZATION_NAME, data.organizationName);
      setOrganizationName(data.organizationName);
    }
  }, [data, setOrganizationName]);

  const handleCreateWorkspace = () => {
    navigate("/workspaces/create");
  };

  return (
    <PageWrapper
      title="Workspaces"
      subTitle={`Workspaces in the ${organizationName} organization`}
      loadingText="Loading workspaces..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Workspaces", path: `/organizations/${id}/workspaces` },
      ]}
      fluid
      actions={
        <Space>
          <Button icon={<ImportOutlined />}>
            <Link to="/workspaces/import">Import workspaces</Link>
          </Button>
          <Button icon={<PlusOutlined />} type="primary" onClick={handleCreateWorkspace}>
            New workspace
          </Button>
        </Space>
      }
    >
      <Flex vertical>
        {id && (
          <WorkspaceFilter
            workspaces={workspaces}
            onFiltered={(filtered) => setFilteredWorkspaces(filtered)}
            organizationId={id}
            onTagsLoaded={(t) => setTags(t)}
            sortOption={sortOption}
            onSortChange={handleSortChange}
            projects={projects}
          />
        )}
        <List
          split={false}
          dataSource={sortedWorkspaces}
          pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
          renderItem={(item) => (
            <List.Item
              style={{ cursor: "pointer" }}
              onClick={() => navigate(`/organizations/${id}/workspaces/${item.id}`)}
            >
              <WorkspaceCard tags={tags} item={item} />
            </List.Item>
          )}
        />
      </Flex>
    </PageWrapper>
  );
}
