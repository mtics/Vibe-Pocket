export function printable(value, maxLength, { allowEmpty = false } = {}) {
  if (typeof value !== "string" || value.length > maxLength || /[\u0000-\u001f\u007f]/.test(value)) return null;
  const trimmed = value.trim();
  return trimmed.length > 0 || allowEmpty ? trimmed : null;
}

export function dictation(value) {
  return typeof value === "string"
    && value.trim().length > 0
    && value.length <= 12_000
    && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value);
}
