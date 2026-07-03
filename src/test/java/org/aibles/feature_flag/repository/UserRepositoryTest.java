package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private UserRepository repository;

    @Test
    void findByEmailResolvesUser() {
        persistUser("member@example.com");

        assertThat(repository.findByEmail("member@example.com"))
                .isPresent()
                .get()
                .extracting(User::getEmail)
                .isEqualTo("member@example.com");
        assertThat(repository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void existsByEmailReflectsPresence() {
        persistUser("member@example.com");

        assertThat(repository.existsByEmail("member@example.com")).isTrue();
        assertThat(repository.existsByEmail("nobody@example.com")).isFalse();
    }
}
