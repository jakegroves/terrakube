import { Layout, ConfigProvider, theme } from "antd";
import { Suspense, useState, useEffect, type ComponentType, type Dispatch, type SetStateAction } from "react";
import {
  RouterProvider,
  createBrowserRouter,
  Outlet,
  useParams,
  useNavigate,
  useOutletContext,
  useLocation,
  useNavigation,
  type RouteObject,
  type LoaderFunctionArgs,
} from "react-router-dom";
import { useAuth } from "../../config/authConfig";
import { getBasePath } from "../../config/basePath";
import { queryClient } from "../../config/queryClient";
import { getThemeConfig } from "../../config/themeConfig";
import { ThemeProvider, useTheme } from "../../context/ThemeContext";
import Login from "../Login/Login";
import "./App.css";
import MainMenu from "./MainMenu";
import { HelpMenu } from "@/components/HelpMenu";
import LoadingFallback from "@/components/LoadingFallback";
import { UserMenu } from "@/components/UserMenu";
import { OrganizationSelector } from "@/components/OrganizationSelector";
import logo from "./white_logo.png";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { FlatOrganization } from "../types";
const { Header, Footer } = Layout;

type AppRouteContext = {
  organizationName: string;
  setOrganizationName: Dispatch<SetStateAction<string>>;
};

// Each loader below is a plain dynamic import (no React.lazy/Suspense): when used via a
// route's `lazy` property, React Router awaits it as part of navigation itself, so
// useNavigation() reflects the chunk download and the router holds the previous page
// (progress bar visible) instead of unmounting to a blank Suspense fallback.

// Organizations
const loadCreateOrganization = () => import("../Organizations/Create").then((m) => m.CreateOrganization);
const loadOrganizationsPickerPage = () =>
  import("@/modules/organizations/OrganizationsPickerPage").then((m) => m.default);
const loadProjectDetailPage = () => import("@/modules/projects/ProjectDetailPage").then((m) => m.default);

// Workspaces
const loadCreateWorkspace = () => import("../Workspaces/Create").then((m) => m.CreateWorkspace);
const loadImportWorkspace = () => import("../Workspaces/Import").then((m) => m.ImportWorkspace);
const loadWorkspaceDetails = () => import("../Workspaces/Details").then((m) => m.WorkspaceDetails);

// Modules and registry
const loadCreateModule = () => import("../Modules/Create").then((m) => m.CreateModule);
const loadModuleDetails = () => import("../Modules/Details").then((m) => m.ModuleDetails);

// Settings
const loadOrganizationSettings = () => import("../Settings/Settings").then((m) => m.OrganizationSettings);
const loadUserSettingsPage = () => import("@/modules/user/UserSettingsPage").then((m) => m.UserSettingsPage);

const useAppRouteContext = () => useOutletContext<AppRouteContext>();

// Builds a route's `lazy` property: awaits the dynamic import, then renders the
// resolved component with whatever props `useProps` computes (typically read from
// useAppRouteContext()/useParams(), mirroring what the old *Route wrapper components did).
const makeLazyRoute = (
  load: () => Promise<ComponentType<any>>,
  useProps?: () => Record<string, unknown>
): Pick<RouteObject, "lazy"> => ({
  lazy: async () => {
    const Component = await load();
    return {
      Component: () => {
        const props = useProps ? useProps() : {};
        return <Component {...props} />;
      },
    };
  },
});

// Same as makeLazyRoute, but also adds a `loader` that primes the react-query cache
// (via queryClient.ensureQueryData) before the router commits the navigation, so the
// component's own useQuery for the same query resolves instantly with no extra fetch.
const makeLazyRouteWithLoader = (
  load: () => Promise<{ Component: ComponentType<any>; query: (...args: any[]) => any }>,
  getQueryArgs: (params: LoaderFunctionArgs["params"]) => any[],
  useProps?: () => Record<string, unknown>
): Pick<RouteObject, "lazy"> => ({
  lazy: async () => {
    const { Component, query } = await load();
    return {
      loader: async ({ params }: LoaderFunctionArgs) => {
        await queryClient.ensureQueryData(query(...getQueryArgs(params)));
        return null;
      },
      Component: () => {
        const props = useProps ? useProps() : {};
        return <Component {...props} />;
      },
    };
  },
});

const createOrganizationRoute = () =>
  makeLazyRoute(loadCreateOrganization, () => {
    const { setOrganizationName } = useAppRouteContext();
    return { setOrganizationName };
  });

const organizationsDetailRoute = () =>
  makeLazyRouteWithLoader(
    () =>
      import("@/modules/organizations/OrganizationDetailsPage").then((m) => ({
        Component: m.default,
        query: m.organizationWorkspacesQuery,
      })),
    (params) => [params.id!],
    () => {
      const { organizationName, setOrganizationName } = useAppRouteContext();
      return { organizationName, setOrganizationName };
    }
  );

const organizationsProjectsRoute = () =>
  makeLazyRouteWithLoader(
    () =>
      import("@/modules/projects/ProjectsPage").then((m) => ({
        Component: m.default,
        query: m.organizationProjectsQuery,
      })),
    (params) => [params.id!],
    () => {
      const { organizationName, setOrganizationName } = useAppRouteContext();
      return { organizationName, setOrganizationName };
    }
  );

const organizationsProjectDetailRoute = () =>
  makeLazyRoute(loadProjectDetailPage, () => {
    const { organizationName, setOrganizationName } = useAppRouteContext();
    return { organizationName, setOrganizationName };
  });

const workspaceDetailsRoute = (selectedTab?: string) =>
  makeLazyRoute(loadWorkspaceDetails, () => {
    const { setOrganizationName } = useAppRouteContext();
    return { setOrganizationName, selectedTab };
  });

const registryRoute = (): Pick<RouteObject, "lazy"> => ({
  lazy: async () => {
    const { Registry, registryOrgNameQuery, registryModulesQuery, registryProvidersQuery } =
      await import("../Modules/Registry");
    return {
      loader: async ({ params, request }: LoaderFunctionArgs) => {
        const orgid = params.orgid!;
        const tab = new URL(request.url).searchParams.get("tab") || "modules";
        await Promise.all([
          queryClient.ensureQueryData(registryOrgNameQuery(orgid)),
          queryClient.ensureQueryData(
            tab === "providers" ? registryProvidersQuery(orgid) : registryModulesQuery(orgid)
          ),
        ]);
        return null;
      },
      Component: () => {
        const { organizationName, setOrganizationName } = useAppRouteContext();
        return <Registry organizationName={organizationName} setOrganizationName={setOrganizationName} />;
      },
    };
  },
});

const publicRegistrySearchRoute = () =>
  makeLazyRouteWithLoader(
    () =>
      import("../Modules/PublicRegistrySearch").then((m) => ({
        Component: m.PublicRegistrySearch,
        query: m.existingRegistryItemsQuery,
      })),
    (params) => [params.orgid!],
    () => {
      const { organizationName } = useAppRouteContext();
      return { organizationName };
    }
  );

const providerDetailsRoute = () =>
  makeLazyRouteWithLoader(
    () =>
      import("../Providers/ProviderDetails").then((m) => ({
        Component: m.ProviderDetails,
        query: m.providerDetailsQuery,
      })),
    (params) => [params.orgid!, params.providerid!],
    () => {
      const { organizationName } = useAppRouteContext();
      return { organizationName };
    }
  );

const moduleDetailsRoute = () =>
  makeLazyRoute(loadModuleDetails, () => {
    const { organizationName } = useAppRouteContext();
    return { organizationName };
  });

const organizationSettingsRoute = (selectedTab?: string, vcsMode?: string, collectionMode?: string) =>
  makeLazyRoute(loadOrganizationSettings, () => ({ selectedTab, vcsMode, collectionMode }));

const collectionSettingsRoute = (mode: "edit" | "detail") =>
  makeLazyRoute(loadOrganizationSettings, () => {
    const { collectionid } = useParams();
    return { selectedTab: "9", collectionMode: mode, collectionId: collectionid };
  });

const NavigationProgressBar = () => {
  const navigation = useNavigation();
  const { token } = theme.useToken();
  const isNavigating = navigation.state !== "idle";

  return (
    <div className="nav-progress-track" aria-hidden={!isNavigating}>
      <div
        className={isNavigating ? "nav-progress-bar nav-progress-bar-active" : "nav-progress-bar"}
        style={{ background: token.colorPrimary }}
      />
    </div>
  );
};

const AppLayout = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const [organizationName, setOrganizationName] = useState<string>("");
  const [orgs, setOrgs] = useState<FlatOrganization[]>([]);
  const { colorScheme, themeMode } = useTheme();

  useEffect(() => {
    const pathname = window.location.pathname;
    const paths = pathname.split("/");
    const orgIdIndex = paths.indexOf("organizations") + 1;

    if (orgIdIndex > 0 && orgIdIndex < paths.length) {
      const orgId = paths[orgIdIndex];
      if (orgId) {
        const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
        const storedOrgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

        if (storedOrgName && storedOrgId === orgId) {
          setOrganizationName(storedOrgName);
        } else {
          organizationService
            .getOrganizationNameGraphQL(orgId)
            .then((orgName) => {
              if (orgName) {
                sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
                sessionStorage.setItem(ORGANIZATION_NAME, orgName);
                setOrganizationName(orgName);
              }
            })
            .catch((err) => {
              console.error("Failed to load organization:", err);
            });
        }
      }
    } else {
      const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
      if (storedOrgName) {
        setOrganizationName(storedOrgName);
      }
    }
  }, []);

  useEffect(() => {
    // Re-fetch on every navigation so newly created/deleted organizations
    // show up in the header dropdown without a full page reload.
    organizationService
      .listOrganizationsGraphQL()
      .then((organizations) => {
        setOrgs(organizations);
      })
      .catch((error) => {
        console.error("Failed to load organizations:", error);
      });
  }, [location.pathname]);

  const handleOrgChange = (orgId: string) => {
    const org = orgs.find((o) => o.id === orgId);
    if (org) {
      sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
      sessionStorage.setItem(ORGANIZATION_NAME, org.name);
      setOrganizationName(org.name);
    }
    navigate(`/organizations/${orgId}/workspaces`);
  };

  return (
    <ConfigProvider theme={getThemeConfig(colorScheme, themeMode)}>
      <NavigationProgressBar />
      <Layout className="layout mh-100">
        <Header>
          <a onClick={() => navigate("/")} style={{ cursor: "pointer" }}>
            <img className="logo" src={logo} alt="Logo"></img>
          </a>
          <OrganizationSelector
            organizationName={organizationName}
            organizations={orgs}
            onOrgChange={handleOrgChange}
            onManageOrgs={() => navigate("/organizations")}
          />
          <div className="menu">
            <MainMenu
              organizationName={organizationName}
              setOrganizationName={setOrganizationName}
              themeMode={themeMode}
            />
          </div>
          <div className="user">
            <HelpMenu />
            <UserMenu />
          </div>
        </Header>
        <Outlet context={{ organizationName, setOrganizationName }} />
        <Footer style={{ textAlign: "center" }}>
          Terrakube {window._env_.REACT_APP_TERRAKUBE_VERSION} ©{new Date().getFullYear()}
        </Footer>
      </Layout>
    </ConfigProvider>
  );
};

const App = () => {
  const auth = useAuth();
  const expiry = auth?.user?.expires_at;
  const basePath = getBasePath();

  // Checking with the expiry time in the localstorage and when it has crossed the access has been revoked so It will clear the local storage and by default with no localstorage object it will route to login page.
  if (auth.isAuthenticated && auth?.user && expiry !== undefined && Math.floor(Date.now() / 1000) > expiry) {
    localStorage.clear();
  }

  if (auth.isLoading) {
    return null;
  }

  if (!auth.isAuthenticated) {
    return <Login />;
  }

  const router = createBrowserRouter(
    [
      {
        path: "/",
        element: <AppLayout />,
        children: [
          {
            path: "/",
            ...makeLazyRoute(loadOrganizationsPickerPage),
          },
          {
            path: "/organizations",
            ...makeLazyRoute(loadOrganizationsPickerPage),
          },
          {
            path: "/organizations/create",
            ...createOrganizationRoute(),
          },
          {
            path: "/organizations/:id/workspaces",
            ...organizationsDetailRoute(),
          },
          {
            path: "/organizations/:id/projects",
            ...organizationsProjectsRoute(),
          },
          {
            path: "/organizations/:orgid/projects/:id",
            ...organizationsProjectDetailRoute(),
          },
          {
            path: "/workspaces/create",
            ...makeLazyRoute(loadCreateWorkspace),
          },
          {
            path: "/workspaces/import",
            ...makeLazyRoute(loadImportWorkspace),
          },
          {
            path: "/workspaces/:id",
            ...workspaceDetailsRoute(),
          },
          {
            path: "/organizations/:orgid/workspaces/:id",
            ...workspaceDetailsRoute(),
          },
          {
            path: "/workspaces/:id/runs",
            ...workspaceDetailsRoute("2"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/runs",
            ...workspaceDetailsRoute("2"),
          },
          {
            path: "/workspaces/:id/runs/:runid",
            ...workspaceDetailsRoute("2"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/runs/:runid",
            ...workspaceDetailsRoute("2"),
          },
          {
            path: "/workspaces/:id/states",
            ...workspaceDetailsRoute("3"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/states",
            ...workspaceDetailsRoute("3"),
          },
          {
            path: "/workspaces/:id/variables",
            ...workspaceDetailsRoute("4"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/variables",
            ...workspaceDetailsRoute("4"),
          },
          {
            path: "/workspaces/:id/schedules",
            ...workspaceDetailsRoute("5"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/schedules",
            ...workspaceDetailsRoute("5"),
          },
          {
            path: "/workspaces/:id/settings",
            ...workspaceDetailsRoute("6"),
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings",
            ...workspaceDetailsRoute("6"),
          },
          {
            path: "/organizations/:orgid/registry",
            ...registryRoute(),
          },
          {
            path: "/organizations/:orgid/registry/search",
            ...publicRegistrySearchRoute(),
          },
          {
            path: "/organizations/:orgid/registry/create",
            ...makeLazyRoute(loadCreateModule),
          },
          {
            path: "/organizations/:orgid/registry/providers/:providerid",
            ...providerDetailsRoute(),
          },
          {
            path: "/organizations/:orgid/registry/:id",
            ...moduleDetailsRoute(),
          },
          {
            path: "/organizations/:orgid/settings",
            ...organizationSettingsRoute(),
          },
          {
            path: "/organizations/:orgid/settings/general",
            ...organizationSettingsRoute("1"),
          },
          {
            path: "/organizations/:orgid/settings/teams",
            ...organizationSettingsRoute("2"),
          },
          {
            path: "/organizations/:orgid/settings/vcs",
            ...organizationSettingsRoute("4"),
          },
          {
            path: "/organizations/:orgid/settings/vcs/new/:vcsName",
            ...organizationSettingsRoute("4", "new"),
          },
          {
            path: "/settings/tokens",
            ...makeLazyRoute(loadUserSettingsPage),
          },
          {
            path: "/settings/theme",
            ...makeLazyRoute(loadUserSettingsPage),
          },
          {
            path: "/organizations/:orgid/settings/ssh",
            ...organizationSettingsRoute("6"),
          },
          {
            path: "/organizations/:orgid/settings/tags",
            ...organizationSettingsRoute("7"),
          },
          {
            path: "/organizations/:orgid/settings/actions",
            ...organizationSettingsRoute("10"),
          },
          {
            path: "/organizations/:orgid/settings/collection",
            ...organizationSettingsRoute("9"),
          },
          {
            path: "/organizations/:orgid/settings/collection/new",
            ...organizationSettingsRoute("9", undefined, "new"),
          },
          {
            path: "/organizations/:orgid/settings/collection/edit/:collectionid",
            ...collectionSettingsRoute("edit"),
          },
          {
            path: "/organizations/:orgid/settings/collection/:collectionid",
            ...collectionSettingsRoute("detail"),
          },
        ],
      },
    ],
    {
      basename: basePath,
    }
  );

  return (
    <ThemeProvider>
      <Suspense fallback={<LoadingFallback />}>
        <RouterProvider router={router} />
      </Suspense>
    </ThemeProvider>
  );
};

export default App;
