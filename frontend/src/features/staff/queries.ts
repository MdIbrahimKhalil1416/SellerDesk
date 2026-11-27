import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { staffApi } from '../../api/staff'
import type { InviteStaffRequest, StaffListParams, StaffMember, UpdateStaffRequest } from '../../types/staff'

/** Cache keys. Everything under ['staff'] belongs to this feature. */
export const staffKeys = {
  all: ['staff'] as const,
  lists: () => [...staffKeys.all, 'list'] as const,
  list: (params: StaffListParams) => [...staffKeys.lists(), params] as const,
  catalog: () => [...staffKeys.all, 'catalog'] as const,
  detail: (id: string) => [...staffKeys.all, 'detail', id] as const,
}

/** keepPreviousData: while the next page or filter loads, the old rows stay visible (no flicker). */
export function useStaffList(params: StaffListParams) {
  return useQuery({
    queryKey: staffKeys.list(params),
    queryFn: () => staffApi.list(params),
    placeholderData: keepPreviousData,
  })
}

/** The catalog only changes with a deploy, so it is fetched once per session. */
export function usePermissionCatalog() {
  return useQuery({ queryKey: staffKeys.catalog(), queryFn: () => staffApi.permissionCatalog(), staleTime: Infinity })
}

export function useStaffMember(id: string | null) {
  return useQuery({
    queryKey: staffKeys.detail(id ?? ''),
    queryFn: () => staffApi.get(id ?? ''),
    enabled: !!id,
  })
}

/** Marks every staff list as stale, so the table refetches. */
function useInvalidateLists() {
  const queryClient = useQueryClient()
  return useCallback(() => queryClient.invalidateQueries({ queryKey: staffKeys.lists() }), [queryClient])
}

/** Member writes return the updated member: put it in the cache and refresh the table. */
function useStoreMember() {
  const queryClient = useQueryClient()
  const invalidateLists = useInvalidateLists()
  return useCallback(
    (member: StaffMember) => {
      queryClient.setQueryData(staffKeys.detail(member.id), member)
      void invalidateLists()
    },
    [queryClient, invalidateLists],
  )
}

export function useUpdateStaff(id: string) {
  const store = useStoreMember()
  return useMutation({ mutationFn: (body: UpdateStaffRequest) => staffApi.update(id, body), onSuccess: store })
}

export function useChangeStaffStatus(id: string) {
  const store = useStoreMember()
  return useMutation({ mutationFn: (status: 'ACTIVE' | 'DISABLED') => staffApi.changeStatus(id, status), onSuccess: store })
}

/*
 * Invitation writes refresh the list on success AND on error (onSettled): an error such as
 * INVITATION_NOT_PENDING or NOT_FOUND means the row on screen is out of date.
 */
export function useInviteStaff() {
  const invalidateLists = useInvalidateLists()
  return useMutation({ mutationFn: (body: InviteStaffRequest) => staffApi.invite(body), onSettled: () => void invalidateLists() })
}

export function useResendInvitation() {
  const invalidateLists = useInvalidateLists()
  return useMutation({ mutationFn: (id: string) => staffApi.resendInvitation(id), onSettled: () => void invalidateLists() })
}

export function useCancelInvitation() {
  const invalidateLists = useInvalidateLists()
  return useMutation({ mutationFn: (id: string) => staffApi.cancelInvitation(id), onSettled: () => void invalidateLists() })
}
