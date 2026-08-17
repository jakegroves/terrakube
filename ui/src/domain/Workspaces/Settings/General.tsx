import { AutoComplete, Button, Form, Input, Select, Spin, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { Agent, Template, TofuRelease, Workspace } from "../../types";
import {
  atomicHeader,
  compareVersions,
  genericHeader,
  getIaCIconById,
  getIaCNameById,
  iacTypes,
  validateTerraformVersion,
} from "../Workspaces";
import projectService from "@/modules/projects/projectService";
import { ProjectModel } from "@/domain/types";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import SettingsSection from "@/modules/layout/SettingsSection/SettingsSection";

type Props = {
  workspaceData: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
  onWorkspaceUpdate?: () => void;
};

type UpdateWorkspaceForm = {
  name: string;
  description?: string;
  folder?: string;
  executionMode: string;
  terraformVersion: string;
  terragruntVersion?: string;
  iacType: string;
  branch: string;
  defaultTemplate?: string;
  executorAgent?: string;
  project?: string;
};

export const WorkspaceGeneral = ({ workspaceData, orgTemplates, manageWorkspace, onWorkspaceUpdate }: Props) => {
  const organizationId = workspaceData.relationships.organization.data.id;
  const id = workspaceData.id;
  const Option = Select;
  const [selectedIac, setSelectedIac] = useState("");
  const currentIacType = selectedIac || workspaceData.attributes?.iacType;
  const { permissions: orgPermissions } = useOrgPermissions();
  const [terraformVersions, setTerraformVersions] = useState<string[]>([]);
  const [agentList, setAgentList] = useState<Agent[]>([]);
  const [projectList, setProjectList] = useState<ProjectModel[]>([]);
  const [waiting, setWaiting] = useState(false);

  // Terragrunt drives a real Terraform binary underneath, so it reuses the /terraform version
  // manifest - there is no /terragrunt endpoint.
  const versionsEndpointIacType = (iacType: string) => (iacType === "tofu" ? "tofu" : "terraform");

  const loadVersions = (iacType: string) => {
    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${versionsEndpointIacType(iacType)}/index.json`;
    axiosInstance.get(versionsApi).then((resp) => {
      const tfVersions = [];
      if (iacType === "tofu") {
        resp.data.forEach((release: TofuRelease) => {
          if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
        });
      } else {
        for (const version in resp.data.versions) {
          if (!version.includes("-")) tfVersions.push(version);
        }
      }
      setTerraformVersions(tfVersions.sort(compareVersions).reverse());
    });
  };

  useEffect(() => {
    setWaiting(true);
    const iacType = workspaceData.attributes?.iacType;
    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${versionsEndpointIacType(iacType)}/index.json`;

    // Parallel load: versions, agent list, and projects
    Promise.all([
      axiosInstance.get(versionsApi),
      axiosInstance.get(`organization/${organizationId}/agent`),
      projectService.listProjects(organizationId),
    ]).then(([versionsRes, agentsRes, projectsRes]) => {
      const tfVersions: string[] = [];
      if (iacType === "tofu") {
        versionsRes.data.forEach((release: TofuRelease) => {
          if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
        });
      } else {
        for (const version in versionsRes.data.versions) {
          if (!version.includes("-")) tfVersions.push(version);
        }
      }
      setTerraformVersions(tfVersions.sort(compareVersions).reverse());
      setAgentList(agentsRes.data.data);
      if (!projectsRes.isError) setProjectList(projectsRes.data);
      setWaiting(false);
    });
  }, [organizationId, workspaceData.attributes?.iacType]);

  const handleIacChange = (iac: string) => {
    setSelectedIac(iac);
    loadVersions(iac);
  };
  const onFinish = (values: UpdateWorkspaceForm) => {
    setWaiting(true);
    const body = {
      "atomic:operations": [
        {
          op: "update",
          href: `/organization/${organizationId}/workspace/${id}`,
          data: {
            type: "workspace",
            id: id,
            attributes: {
              name: values.name,
              description: values.description,
              folder: values.folder,
              executionMode: values.executionMode,
              terraformVersion: values.terraformVersion,
              terragruntVersion: values.iacType === "terragrunt" ? values.terragruntVersion : undefined,
              iacType: values.iacType,
              branch: values.branch,
              defaultTemplate: values.defaultTemplate,
            },
          },
        },
      ],
    };

    try {
      axiosInstance.post("/operations", body, atomicHeader).then((response) => {
        if (response.status === 200) {
          message.success("workspace updated successfully");
          onWorkspaceUpdate?.();
        } else {
          message.error("workspace update failed");
        }
        setWaiting(false);
      });
    } catch (error) {
      console.error("error updating workspace:", error);
      message.error("workspace update failed");
      setWaiting(false);
    }

    let bodyAgent;

    if (values.executorAgent === "default") {
      bodyAgent = {
        data: null,
      };
    } else {
      bodyAgent = {
        data: {
          type: "agent",
          id: values.executorAgent,
        },
      };
    }

    axiosInstance
      .patch(`/organization/${organizationId}/workspace/${id}/relationships/agent`, bodyAgent, genericHeader)
      .then((response) => {
        if (response.status === 204) {
          console.log("Workspace agent updated successfully");
        } else {
          console.log("Workspace agent update failed");
        }
      });

    const bodyProject =
      values.project && values.project !== "none" ? { data: { type: "project", id: values.project } } : { data: null };

    axiosInstance
      .patch(`/organization/${organizationId}/workspace/${id}/relationships/project`, bodyProject, genericHeader)
      .then((response) => {
        if (response.status === 204) {
          console.log("Workspace project updated successfully");
        } else {
          console.log("Workspace project update failed");
        }
      });
  };

  return (
    <div style={{ width: "100%" }} className="generalSettings">
      <Typography.Title level={1} style={{ margin: 0 }}>
        General Settings
      </Typography.Title>
      <p>
        Adjust the settings for this workspace. These settings control how the workspace behaves, including execution
        mode, IaC configuration, and security options.
      </p>
      <Spin spinning={waiting}>
        <Form
          onFinish={onFinish}
          requiredMark={false}
          initialValues={{
            name: workspaceData.attributes?.name,
            description: workspaceData.attributes?.description,
            folder: workspaceData.attributes?.folder,
            executionMode: workspaceData.attributes?.executionMode,
            terraformVersion: workspaceData.attributes?.terraformVersion,
            terragruntVersion: workspaceData.attributes?.terragruntVersion,
            iacType: workspaceData.attributes?.iacType,
            branch: workspaceData.attributes?.branch,
            defaultTemplate: workspaceData.attributes?.defaultTemplate,
            executorAgent:
              workspaceData.relationships.agent?.data?.id == null
                ? "default"
                : workspaceData.relationships.agent.data?.id,
            project: workspaceData.relationships.project?.data?.id ?? "none",
          }}
          layout="vertical"
          name="form-settings"
        >
          <SettingsSection title="Identity">
            <Form.Item
              name="name"
              rules={[
                { required: true },
                {
                  pattern: /^[A-Za-z0-9_-]+$/,
                  message: "Only dashes, underscores, and alphanumeric characters are permitted.",
                },
              ]}
              label="Name"
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>

            <Form.Item valuePropName="value" name="description" label="Description" extra="Optional">
              <Input.TextArea rows={5} placeholder="Workspace description" disabled={!manageWorkspace} />
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="Execution Mode"
            description="Select the execution mode for this workspace. Remote indicates Terrakube will run plans and applies. Local indicates users should run locally with remote state."
          >
            <Form.Item
              name="executionMode"
              label="Execution Mode"
              extra={
                "Local indicates users should run " +
                getIaCNameById(currentIacType) +
                " " +
                "locally with remote state/cloud block and just upload the state to Terrakube. Remote " +
                "indicates Terrakube will run plans and apply. Informational only."
              }
            >
              <Select defaultValue={workspaceData.attributes.executionMode} disabled={!manageWorkspace}>
                <Option key="remote">remote</Option>
                <Option key="local">local</Option>
              </Select>
            </Form.Item>
            <Form.Item
              name="executorAgent"
              label="Executor agent to run the job"
              extra="Use this option to select which executor agent will run the job remotely"
            >
              <Select
                defaultValue={workspaceData.attributes.moduleSshKey}
                placeholder="select Job Agent"
                disabled={!manageWorkspace}
              >
                {agentList.map(function (agentKey) {
                  return <Option key={agentKey?.id}>{agentKey?.attributes?.name}</Option>;
                })}
                <Option key="default">default</Option>
              </Select>
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="IaC Configuration"
            description="Configure the Infrastructure as Code tool and version used for this workspace."
          >
            <Form.Item
              name="iacType"
              label="Select IaC type "
              extra="IaC type when running the workspace (Example: terraform or tofu) "
            >
              <Select
                defaultValue={workspaceData.attributes?.iacType}
                onChange={handleIacChange}
                disabled={!manageWorkspace}
              >
                {iacTypes.map(function (iacType) {
                  return (
                    <Option key={iacType.id}>
                      {getIaCIconById(iacType.id)} {iacType.name}{" "}
                    </Option>
                  );
                })}
              </Select>
            </Form.Item>
            <Form.Item
              name="terraformVersion"
              label={
                (currentIacType === "terragrunt" ? "Terraform" : getIaCNameById(currentIacType)) + " Version"
              }
              rules={[{ validator: validateTerraformVersion(terraformVersions) }]}
              extra={
                "The version of " +
                (currentIacType === "terragrunt" ? "Terraform" : getIaCNameById(currentIacType)) +
                (currentIacType === "terragrunt" ? " Terragrunt drives underneath" : "") +
                " to use for this workspace. It will not upgrade automatically. Version constraints are also supported (e.g. ~>1.11.0, >=1.5.7 <1.9.0)."
              }
            >
              <AutoComplete
                disabled={!manageWorkspace}
                options={terraformVersions.map((v) => ({ value: v }))}
                filterOption={(input, option) => (option?.value ?? "").includes(input)}
                placeholder="e.g. 1.11.0 or ~>1.11.0"
              />
            </Form.Item>
            {currentIacType === "terragrunt" && (
              <Form.Item
                name="terragruntVersion"
                label="Terragrunt Version"
                extra="The Terragrunt CLI version to use for this workspace. Leave blank to use the default supported version."
              >
                <Input disabled={!manageWorkspace} placeholder="e.g. 0.67.16" />
              </Form.Item>
            )}
            <Form.Item
              name="folder"
              label={getIaCNameById(currentIacType) + " Working Directory"}
              extra={
                "The directory that " +
                getIaCNameById(currentIacType) +
                " will execute within. This defaults to the root of your repository and is typically set to a subdirectory matching the environment when multiple environments exist within the same repository."
              }
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>
            <Form.Item
              name="branch"
              label="Default Branch"
              tooltip="The branch from which the runs are kicked off, this is used for runs issued from the UI."
              extra="Don't update the value when using CLI Driven workflows. This is only used in VCS driven workflow."
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="Default Template"
            description={
              <>
                Template used for the <code>terrakube apply</code> PR comment command, and to pre-fill the template
                when manually creating a run.
              </>
            }
          >
            <Form.Item
              name="defaultTemplate"
              label="Default template for terrakube apply comments and manual runs"
              extra="Default template for terrakube apply comments and manual runs"
            >
              <Select
                defaultValue={workspaceData.attributes.defaultTemplate}
                placeholder="select default template"
                disabled={!manageWorkspace}
              >
                {orgTemplates.map(function (template) {
                  return <Option key={template?.id}>{template?.attributes?.name}</Option>;
                })}
              </Select>
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="Project"
            description="Assign this workspace to a project for easier organization and filtering."
          >
            <Form.Item
              name="project"
              label="Project"
              extra="Optional. Assigning a project lets you group and filter workspaces."
            >
              <Select placeholder="No project" disabled={!manageWorkspace}>
                {orgPermissions.manageWorkspace && <Option key="none">(No project)</Option>}
                {projectList.map((p) => (
                  <Option key={p.id}>{p.name}</Option>
                ))}
              </Select>
            </Form.Item>
          </SettingsSection>

          <Form.Item>
            <Button type="primary" htmlType="submit" disabled={!manageWorkspace}>
              Save settings
            </Button>
          </Form.Item>
        </Form>
      </Spin>
    </div>
  );
};
