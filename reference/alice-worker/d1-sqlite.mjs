import Database from "better-sqlite3";

class StatementCompat {
  constructor(db, sql, params = []) {
    this.db = db;
    this.sql = sql;
    this.params = params;
  }

  bind(...params) {
    return new StatementCompat(this.db, this.sql, params);
  }

  async run() {
    const result = this.db.prepare(this.sql).run(...this.params);
    return { success: true, meta: { changes: result.changes } };
  }

  async all() {
    return { results: this.db.prepare(this.sql).all(...this.params) };
  }

  async first() {
    return this.db.prepare(this.sql).get(...this.params) ?? null;
  }
}

export class D1SqliteCompat {
  constructor(path) {
    this.db = new Database(path);
    this.db.pragma("journal_mode = WAL");
  }

  prepare(sql) {
    return new StatementCompat(this.db, sql);
  }

  async batch(statements) {
    const transaction = this.db.transaction(() => {
      for (const statement of statements) {
        this.db.prepare(statement.sql).run(...statement.params);
      }
    });
    transaction();
    return statements.map(() => ({ success: true }));
  }
}
