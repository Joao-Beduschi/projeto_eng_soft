// Keep the browser's validation UI while using messages in the selected application language.
document.addEventListener("invalid", ({ target }) => {
  if (!(target instanceof HTMLInputElement) || target.validity.customError) return;

  const validity = target.validity;
  const messages = target.dataset;
  const message = validity.valueMissing ? messages.validationRequired
    : validity.typeMismatch ? messages.validationType
    : validity.tooShort || validity.tooLong ? messages.validationLength
    : null;
  if (message) target.setCustomValidity(message);
}, true);

document.addEventListener("input", ({ target }) => {
  if (target instanceof HTMLInputElement
      && (target.dataset.validationRequired || target.dataset.validationType || target.dataset.validationLength)) {
    target.setCustomValidity("");
  }
});
