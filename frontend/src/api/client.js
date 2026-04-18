const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";

export async function getStates() {
  const response = await fetch(`${API_BASE}/states`);
  if (!response.ok) throw new Error("Failed to load states");
  return response.json();
}

export async function getStateDetail(stateCode) {
  const response = await fetch(`${API_BASE}/state/${stateCode}`);
  if (!response.ok) throw new Error("Failed to load state details");
  return response.json();
}
