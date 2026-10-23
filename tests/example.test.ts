import { describe, expect, it } from 'vitest';
import { add } from '../src/example.js';

describe('add', () => {
  it('should correctly add two numbers', () => {
    expect(add(2, 3)).toBe(5);
    expect(add(-1, 1)).toBe(0);
  });
});
