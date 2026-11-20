import { useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog'
import type { Product } from '../../../types/product'
import { productErrorMessage } from '../productErrors'
import { useChangeProductStatus } from '../queries'

/** Archive / unarchive with a confirmation. Archived products cannot be added to new orders. */
export function ArchiveButton({ product, onError }: { product: Product; onError: (message: string | null) => void }) {
  const [open, setOpen] = useState(false)
  const changeStatus = useChangeProductStatus(product.id)
  const archiving = product.status === 'ACTIVE'

  function confirm() {
    onError(null)
    changeStatus.mutate(archiving ? 'ARCHIVED' : 'ACTIVE', {
      onSettled: () => setOpen(false),
      onError: (e) => onError(productErrorMessage(e)),
    })
  }

  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>
        {archiving ? 'Archive' : 'Unarchive'}
      </Button>
      <ConfirmDialog
        open={open}
        title={archiving ? `Archive "${product.name}"?` : `Unarchive "${product.name}"?`}
        confirmLabel={archiving ? 'Archive' : 'Unarchive'}
        danger={archiving}
        loading={changeStatus.isPending}
        onConfirm={confirm}
        onCancel={() => setOpen(false)}
      >
        {archiving
          ? 'The product is hidden from the order form and the default product list. Existing orders are not affected. You can unarchive it at any time.'
          : 'The product becomes active again and can be added to new orders.'}
      </ConfirmDialog>
    </>
  )
}
