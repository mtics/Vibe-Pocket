export class PocketError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "PocketError";
    this.status = status;
    this.code = code;
  }
}
