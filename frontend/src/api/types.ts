// Types generated from the backend's OpenAPI spec (npm run gen:api -> schema.d.ts), aliased for readability.
// Money fields are typed as numbers by the generator but travel as strings ("4802.50"): format with lib/format.
import type { components } from './schema'

type S = components['schemas']

export type Invoice = S['Invoice']
export type InvoiceLine = S['InvoiceLine']
export type InvoiceSummary = S['InvoiceSummary']
export type Flag = S['Flag']
export type Vendor = S['Vendor']
export type VendorRow = S['VendorRow']
export type Payment = S['Payment']
// cardAdvice: Brim card optimizer (bonus 5), present on SCHEDULED rows. Typed by hand until gen:api is re-run.
export interface CardAdvice {
  verdict: 'SWITCH' | 'ON_CARD' | 'KEEP' | 'NOT_ELIGIBLE'
  headline: string
  chargeDate: string | null
  statementClose: string | null
  cardDueDate: string | null
  floatDays: number
  floatValueCad: string
  rewardsCad: string
  benefitCad: string
  notes: string[]
}
export type PaymentRow = S['PaymentRow'] & { cardAdvice?: CardAdvice | null }
export interface CardSummary { count: number; amountCad: string; benefitCad: string; avgFloatDays: number }
export interface CardOpportunity {
  terms: { graceDays: number; rewardsRate: string; costOfCashAnnual: string; perTxnLimitCad: string }
  scheduledSwitchable: CardSummary
  paidByEftCouldHaveBeenCard: CardSummary
  paidByCard: CardSummary
  cardAcceptingVendors: number
  byVendor: { vendorId: string; vendorName: string; payments: number; amountCad: string; benefitCad: string }[]
}
export type PaymentBatch = S['PaymentBatch']
export type EmailMessage = S['EmailMessage']
export type AuditEvent = S['AuditEvent']
export type ApprovalPolicy = S['ApprovalPolicy']
export type QueueItem = S['QueueItem']
export type AppUser = S['AppUser']
export type UserSummary = S['UserSummary']
export type GlAccount = S['GlAccount']
export type Department = S['Department']
export type TaxCode = S['TaxCode']
export type BankChangeRequest = S['BankChangeRequest']

export type Role = 'EMP' | 'CLK' | 'APR' | 'FM' | 'ADM' | 'AUD'

export interface Me {
  id: string
  fullName: string
  title: string
  email: string
  role: Role
  departmentId: string | null
  managerId: string | null
  approvalLimitCad: string
  status: string
  actingFor: { delegationId: number; userId: string; fullName: string; since: string }[]
  delegatedTo: { delegationId: number; userId: string; since: string }[]
  businessDate: string
}

export interface ApprovalStepRow {
  id: number
  invoiceId: string
  seq: number
  requiredRole: string
  assignedUserId: string
  onBehalfOfUserId: string | null
  policyIds: string | null
  routingNotes: string | null
  decision: string
  actedBy: string | null
  actedOnBehalfOf: string | null
  actedAt: string | null
  comment: string | null
  historical: boolean
}

export interface InvoiceTaxRow {
  id: number
  label: string
  taxCode: string | null
  rate: string | null
  amount: string
}

export interface ExtractionRow {
  id: number
  method: string
  model: string | null
  rawText: string | null
  rawJson: string | null
  confidenceJson: string | null
  error: string | null
  fromCache: boolean
  durationMs: number
}

export interface InvoiceDetail {
  invoice: Invoice
  vendor: Vendor | null
  lines: InvoiceLine[]
  taxes: InvoiceTaxRow[]
  flags: Flag[]
  approvalSteps: ApprovalStepRow[]
  payments: Payment[]
  extractions: ExtractionRow[]
  history: AuditEvent[]
  users: Record<string, string>
  allowedNext: string[]
  recommendation?: { verdict: 'APPROVE' | 'LOOK_CLOSER' | 'HOLD'; headline: string; reasons: string[] }
}

export interface RouteStep {
  seq: number
  role: string
  assigneeId: string
  onBehalfOfId: string | null
  policyIds: string[]
  notes: string[]
}

export interface RouteResult {
  steps: RouteStep[]
  amountCad: string
  matchedPolicies: string[]
  problems: string[]
}
