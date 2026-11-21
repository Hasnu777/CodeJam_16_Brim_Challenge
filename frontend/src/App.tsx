import { Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import AppLayout from './components/AppLayout'
import { Loading } from './components/bits'
import AuditPage from './pages/AuditPage'
import HomePage from './pages/HomePage'
import InboxPage from './pages/InboxPage'
import InvoiceDetailPage from './pages/InvoiceDetailPage'
import InvoiceListPage from './pages/InvoiceListPage'
import LoginPage from './pages/LoginPage'
import MyApprovalsPage from './pages/MyApprovalsPage'
import PaymentsPage from './pages/PaymentsPage'
import PoliciesPage from './pages/PoliciesPage'
import VendorDetailPage from './pages/VendorDetailPage'
import VendorsPage from './pages/VendorsPage'

export default function App() {
  const { me, loading } = useAuth()
  if (loading) return <Loading />
  if (!me) return <LoginPage />
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="inbox" element={<InboxPage />} />
        <Route path="invoices" element={<InvoiceListPage />} />
        <Route path="invoices/:id" element={<InvoiceDetailPage />} />
        <Route path="approvals" element={<MyApprovalsPage />} />
        <Route path="payments" element={<PaymentsPage />} />
        <Route path="vendors" element={<VendorsPage />} />
        <Route path="vendors/:id" element={<VendorDetailPage />} />
        <Route path="policies" element={<PoliciesPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="*" element={<HomePage />} />
      </Route>
    </Routes>
  )
}
