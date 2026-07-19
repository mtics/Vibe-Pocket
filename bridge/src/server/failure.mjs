export class Failure extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "Failure";
    this.status = status;
    this.code = code;
  }
}
