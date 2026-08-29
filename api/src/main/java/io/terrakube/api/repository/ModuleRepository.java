package io.terrakube.api.repository;

import java.util.List;
import java.util.UUID;

import io.terrakube.api.rs.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.module.Module;

public interface ModuleRepository extends JpaRepository<Module, UUID> {

    @org.springframework.data.jpa.repository.Query("select m.organization.id, count(m) from Module m group by m.organization.id")
    List<Object[]> countByOrganization();

    List<Module> findByOrganizationId(UUID organizationId);
    List<Module> findByOrganizationIn(List<Organization> organizations);
    List<Module> findAllByOrganizationIdAndNameAndProvider(UUID organizationId, String name, String provider);
}
