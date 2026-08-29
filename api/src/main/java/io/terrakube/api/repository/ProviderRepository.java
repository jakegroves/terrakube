package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.provider.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {

    @org.springframework.data.jpa.repository.Query("select p.organization.id, count(p) from Provider p group by p.organization.id")
    List<Object[]> countByOrganization();

    List<Provider> findByOrganizationId(UUID organizationId);
    List<Provider> findByOrganizationIn(List<Organization> organizations);
}
