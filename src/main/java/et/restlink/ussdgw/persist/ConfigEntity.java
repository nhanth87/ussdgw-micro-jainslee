package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ussd_config")
public class ConfigEntity extends PanacheEntityBase {
    @Id
    @Column(name = "config_key", length = 128)
    public String configKey;

    @Column(name = "config_value", length = 4096, nullable = false)
    public String configValue;
}
