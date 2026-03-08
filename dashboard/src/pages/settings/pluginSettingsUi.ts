export function buttonVariant(variant: string | null | undefined): string {
  return variant != null && variant.trim().length > 0 ? variant : 'secondary';
}
