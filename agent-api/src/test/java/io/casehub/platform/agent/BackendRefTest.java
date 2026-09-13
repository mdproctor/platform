package io.casehub.platform.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendRefTest {

    @Test
    void equalityByKeyAndInstanceId() {
        var ref1 = new BackendRef("claude", "vertex");
        var ref2 = new BackendRef("claude", "vertex");
        assertThat(ref1).isEqualTo(ref2);
        assertThat(ref1.hashCode()).isEqualTo(ref2.hashCode());
    }

    @Test
    void differentInstanceIdNotEqual() {
        var ref1 = new BackendRef("claude", "default");
        var ref2 = new BackendRef("claude", "vertex");
        assertThat(ref1).isNotEqualTo(ref2);
    }

    @Test
    void differentKeyNotEqual() {
        var ref1 = new BackendRef("claude", "default");
        var ref2 = new BackendRef("openai", "default");
        assertThat(ref1).isNotEqualTo(ref2);
    }

    @Test
    void nullKeyThrows() {
        assertThatThrownBy(() -> new BackendRef(null, "default"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullInstanceIdThrows() {
        assertThatThrownBy(() -> new BackendRef("claude", null))
                .isInstanceOf(NullPointerException.class);
    }
}
