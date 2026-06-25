import { registerPlugin } from '@capacitor/core';

const Overlay = registerPlugin('Overlay');

export async function requestOverlayPermission() {
  return await Overlay.requestPermission();
}

export async function hasOverlayPermission() {
  const { value } = await Overlay.hasPermission();
  return value;
}

export async function showOverlay() {
  return await Overlay.show();
}

export async function hideOverlay() {
  return await Overlay.hide();
}
