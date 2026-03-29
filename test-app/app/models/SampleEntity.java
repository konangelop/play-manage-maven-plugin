package models;

import javax.persistence.Entity;
import javax.persistence.Id;

/**
 * Minimal JPA entity to verify that javax.persistence classes
 * are loadable in the dev-mode classloader.
 */
@Entity
public class SampleEntity {

    @Id
    private Long id;

    private String name;
}
