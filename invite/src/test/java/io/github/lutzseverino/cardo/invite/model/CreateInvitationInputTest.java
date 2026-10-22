package io.github.lutzseverino.cardo.invite.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateInvitationInputTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private CreateInvitationInput input(InvitationMessage message) {
    return new CreateInvitationInput(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "clinic:clinic",
        "employee@example.com",
        UUID.randomUUID(),
        URI.create("https://clinic.example.com/invitations"),
        message);
  }

  @Test
  void acceptsNoMessage() {
    assertThat(validator.validate(input(null))).isEmpty();
  }

  @Test
  void acceptsAMessageWithExactlyOnePlaceholder() {
    assertThat(
            validator.validate(
                input(new InvitationMessage("Join the clinic", "Click {{acceptUrl}} to join"))))
        .isEmpty();
  }

  @Test
  void rejectsABodyWithoutThePlaceholder() {
    assertThat(validator.validate(input(new InvitationMessage("Hi", "No placeholder here"))))
        .isNotEmpty();
  }

  @Test
  void rejectsABodyWithThePlaceholderTwice() {
    assertThat(
            validator.validate(
                input(new InvitationMessage("Hi", "{{acceptUrl}} and {{acceptUrl}}"))))
        .isNotEmpty();
  }

  @Test
  void rejectsABlankSubject() {
    assertThat(validator.validate(input(new InvitationMessage("   ", "Go {{acceptUrl}}"))))
        .isNotEmpty();
  }

  @Test
  void rejectsABlankBody() {
    assertThat(validator.validate(input(new InvitationMessage("Hi", "   ")))).isNotEmpty();
  }

  @Test
  void rejectsASubjectWithANewline() {
    assertThat(validator.validate(input(new InvitationMessage("Hi\nthere", "Go {{acceptUrl}}"))))
        .isNotEmpty();
  }

  @Test
  void rejectsASubjectWithACarriageReturn() {
    assertThat(validator.validate(input(new InvitationMessage("Hi\rthere", "Go {{acceptUrl}}"))))
        .isNotEmpty();
  }

  @Test
  void rejectsAnOverLengthSubject() {
    assertThat(
            validator.validate(input(new InvitationMessage("x".repeat(201), "Go {{acceptUrl}}"))))
        .isNotEmpty();
  }

  @Test
  void rejectsAnOverLengthBody() {
    assertThat(
            validator.validate(
                input(new InvitationMessage("Hi", "{{acceptUrl}}" + "x".repeat(10000)))))
        .isNotEmpty();
  }
}
