type InvoiceViewerRole = 'EMP' | 'CLK' | 'APR' | 'FM' | 'ADM' | 'AUD'

/**
 * Every working role gets the counted error banner and pop-up; employees (who only see bills they submitted) keep
 * the findings inline. Presentation only: API authorization and flag resolution rules are unchanged.
 */
export function usesInvoiceErrorPopout(role: InvoiceViewerRole | string | undefined): boolean {
  return role !== undefined && role !== 'EMP'
}
