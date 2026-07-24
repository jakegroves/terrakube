import { Button, Form, Input, Modal, Table, message } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import { ErrorInformation } from "@/modules/api/types";
import { ProjectModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
};

// Shared between the route loader (which primes the cache before navigation
// commits) and this component's own useQuery, so the two never fetch twice.
export const organizationProjectsQuery = (orgId: string) =>
  queryOptions({
    queryKey: ["organizationProjects", orgId],
    queryFn: async () => {
      const response = await projectService.listProjects(orgId);
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

export default function ProjectsPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { permissions } = useOrgPermissions(id);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();

  const { data, isLoading: loading, error: queryError } = useQuery(organizationProjectsQuery(id!));
  const projects = data ?? [];
  const error = queryError as ErrorInformation | undefined;

  useEffect(() => {
    const stored = sessionStorage.getItem(ORGANIZATION_NAME);
    if (stored) setOrganizationName(stored);
  }, [setOrganizationName]);

  const openCreate = () => {
    form.resetFields();
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await projectService.createProject(id!, values);
      message.success("Project created");
      setModalOpen(false);
      queryClient.invalidateQueries({ queryKey: ["organizationProjects", id] });
    } catch (err: any) {
      if (err?.errorFields) return;
      if (err?.response?.status === 403) {
        message.error(
          <span>
            You are not authorized to create projects. <br /> Please contact your administrator and request the{" "}
            <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else {
        message.error(err?.message ?? "An error occurred");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      render: (_: any, record: ProjectModel) => (
        <Button
          type="link"
          style={{ padding: 0 }}
          onClick={() => navigate(`/organizations/${id}/projects/${record.id}`)}
        >
          {record.name}
        </Button>
      ),
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
    },
  ];

  return (
    <PageWrapper
      title="Projects"
      subTitle={`Projects in the ${organizationName} organization`}
      loadingText="Loading projects..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Projects", path: `/organizations/${id}/projects` },
      ]}
      fluid
      actions={
        <Button icon={<PlusOutlined />} type="primary" onClick={openCreate} disabled={!permissions.manageWorkspace}>
          New project
        </Button>
      }
    >
      <Table
        dataSource={projects}
        columns={columns}
        rowKey="id"
        pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
      />
      <Modal
        title="New project"
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        okText="Create"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </PageWrapper>
  );
}
