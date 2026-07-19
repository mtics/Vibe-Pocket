export class Shutdown {
  #servers;
  #service;
  #events;
  #cleanup;
  #closing = null;

  constructor({ servers, service, events, cleanup = () => {} }) {
    this.#servers = servers;
    this.#service = service;
    this.#events = events;
    this.#cleanup = cleanup;
  }

  close() {
    if (!this.#closing) this.#closing = this.#close();
    return this.#closing;
  }

  async #close() {
    let failure = null;
    const attempt = async (operation) => {
      try {
        await operation();
      } catch (error) {
        failure ??= error;
      }
    };
    const closed = this.#servers.map((server) => server.stopAccepting());
    await attempt(() => Promise.all(this.#servers.map((server) => server.drain())));
    await attempt(() => this.#service.stop());
    await attempt(() => this.#events.close());
    await attempt(() => Promise.all(closed));
    await attempt(() => this.#cleanup());
    await attempt(() => this.#service.dispose());
    if (failure) throw failure;
  }
}
