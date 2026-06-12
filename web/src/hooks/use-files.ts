import { useEffect, useMemo, useRef, useState } from "react";
import {
  type DownloadStatus,
  type FileFilter,
  type TelegramFile,
  type Thumbnail,
  type TransferStatus,
} from "@/lib/types";
import useSWRInfinite from "swr/infinite";
import { useWebsocket } from "@/hooks/use-websocket";
import { WebSocketMessageType } from "@/lib/websocket-types";
import { useLocalStorage } from "@/hooks/use-local-storage";
import { useDebounce, useDebouncedCallback } from "use-debounce";

const DEFAULT_FILTERS: FileFilter = {
  search: "",
  type: "media",
  downloadStatus: undefined,
  transferStatus: undefined,
  offline: false,
  tags: [],
};

type FileResponse = {
  files: TelegramFile[];
  count: number;
  nextFromMessageId: number;
};

export function useFiles(
  accountId: string,
  chatId: string,
  messageThreadId?: number,
  link?: string,
) {
  const noAccountSpecified = accountId === "-1" && chatId === "-1";
  const url = noAccountSpecified
    ? "/files"
    : `/telegram/${accountId}/chat/${chatId}/files`;
  const { lastJsonMessage } = useWebsocket();
  const [latestFilesStatus, setLatestFileStatus] = useState<
    Record<
      string,
      {
        fileId: number;
        downloadStatus: DownloadStatus;
        localPath?: string;
        completionDate?: number;
        downloadedSize: number;
        transferStatus?: TransferStatus;
        thumbnailFile?: Thumbnail;
        removed?: boolean;
      }
    >
  >({});
  const [filters, setFilters, clearFilters] = useLocalStorage<FileFilter>(
    "telegramFileListFilter",
    { ...DEFAULT_FILTERS, offline: noAccountSpecified },
  );
  const filtersRef = useRef(filters);
  const isFilterChangingRef = useRef(false);
  const getKey = (page: number, previousPageData: FileResponse) => {
    // When filtering by download status, we need offline=true to use database filter
    const hasDownloadStatusFilter = (filters.downloadStatuses && filters.downloadStatuses.length > 0) || filters.downloadStatus;
    const params = new URLSearchParams({
      ...(filters.search && {
        search: window.encodeURIComponent(filters.search),
      }),
      ...(filters.type && { type: filters.type }),
      // Use multi-select downloadStatuses if available, otherwise fall back to single downloadStatus
      ...(filters.downloadStatuses && filters.downloadStatuses.length > 0 && {
        downloadStatuses: filters.downloadStatuses.join(","),
      }),
      ...(filters.downloadStatus && !filters.downloadStatuses && { downloadStatus: filters.downloadStatus }),
      ...(filters.transferStatus && { transferStatus: filters.transferStatus }),
      // Automatically set offline=true when filtering by download status, or use explicit offline filter
      ...((hasDownloadStatusFilter || filters.offline) && { offline: "true" }),
      ...(filters.tags.length > 0 && {
        tags: filters.tags.join(","),
      }),
      ...(messageThreadId && { messageThreadId: messageThreadId.toString() }),
      ...(link && { link: window.encodeURIComponent(link) }),
      ...(filters.dateType && { dateType: filters.dateType }),
      ...(filters.dateRange && { dateRange: filters.dateRange.join(",") }),
      ...(filters.sizeRange && { sizeRange: filters.sizeRange.join(",") }),
      ...(filters.sizeUnit && { sizeUnit: filters.sizeUnit }),
      ...(filters.sort && { sort: filters.sort }),
      ...(filters.order && { order: filters.order }),
    });

    if (page === 0) {
      return `${url}?${params.toString()}`;
    }

    if (!previousPageData) {
      return null;
    }

    params.set("fromMessageId", previousPageData.nextFromMessageId.toString());
    if (filters.offline && previousPageData.files.length > 0) {
      const lastFile =
        previousPageData.files[previousPageData.files.length - 1];
      if (filters.sort === "size") {
        params.set("fromSortField", lastFile!.size.toString());
      } else if (filters.sort === "completion_date") {
        params.set("fromSortField", lastFile!.completionDate.toString());
      } else if (filters.sort === "date") {
        params.set("fromSortField", lastFile!.date.toString());
      } else if (filters.sort === "reaction_count") {
        params.set("fromSortField", lastFile!.reactionCount.toString());
      }
    }
    return `${url}?${params.toString()}`;
  };

  const {
    data: pages,
    isLoading,
    isValidating,
    size,
    setSize,
    error,
    mutate,
  } = useSWRInfinite<FileResponse, Error>(getKey, {
    revalidateFirstPage: false,
    keepPreviousData: true,
  });

  const [debounceLoading] = useDebounce(isLoading || isValidating, 500, {
    leading: true,
    maxWait: 1000,
  });

  // A thumbnail finished downloading in the background; refetch so the list picks up the
  // crisp thumbnailFile. Debounced to coalesce the bursts that happen while browsing.
  const debouncedThumbnailRefetch = useDebouncedCallback(() => {
    void mutate();
  }, 1500);

  useEffect(() => {
    if (lastJsonMessage?.type !== WebSocketMessageType.FILE_STATUS) {
      return;
    }
    const data = lastJsonMessage.data as {
      fileId: number;
      uniqueId: string;
      downloadStatus: DownloadStatus;
      localPath: string;
      completionDate: number;
      downloadedSize: number;
      transferStatus?: TransferStatus;
      thumbnailFile?: Thumbnail;
      removed?: boolean;
      type?: string;
    };

    if (data.type === "thumbnail") {
      debouncedThumbnailRefetch();
      return;
    }

    if (data.removed) {
      setLatestFileStatus((prev) => ({
        ...prev,
        [data.uniqueId]: {
          fileId: data.fileId,
          downloadStatus: "idle",
          localPath: undefined,
          completionDate: undefined,
          downloadedSize: 0,
          transferStatus: "idle",
          removed: true,
        },
      }));
      return;
    }

    setLatestFileStatus((prev) => ({
      ...prev,
      [data.uniqueId]: {
        fileId: data.fileId,
        downloadStatus:
          data.downloadStatus ?? prev[data.uniqueId]?.downloadStatus,
        localPath: data.localPath ?? prev[data.uniqueId]?.localPath,
        completionDate:
          data.completionDate ?? prev[data.uniqueId]?.completionDate,
        downloadedSize:
          data.downloadedSize ?? prev[data.uniqueId]?.downloadedSize,
        transferStatus:
          data.transferStatus ?? prev[data.uniqueId]?.transferStatus,
        thumbnailFile: data.thumbnailFile ?? prev[data.uniqueId]?.thumbnailFile,
      },
    }));
  }, [lastJsonMessage]);

  useEffect(() => {
    if (noAccountSpecified && !filters.offline) {
      setFilters((prev) => ({
        ...prev,
        offline: true,
      }));
    }
  }, [filters.offline, noAccountSpecified, setFilters]);

  // Watch for filter changes and trigger refetch
  useEffect(() => {
    // Only refetch if filters actually changed (not just initial mount)
    if (isFilterChangingRef.current) {
      isFilterChangingRef.current = false;
      // Reset to page 1 and refetch with new filters
      setSize(1).then(() => {
        mutate();
      });
    }
    filtersRef.current = filters;
  }, [filters, mutate, setSize]);

  const files = useMemo(() => {
    if (!pages) return [];
    const files: TelegramFile[] = [];
    pages.forEach((page) => {
      page.files.forEach((file) => {
        if (file.originalDeleted && latestFilesStatus[file.uniqueId]?.removed) {
          return;
        }
        if (filters.showMissingOriginal === false && file.originalDeleted) {
          return;
        }
        files.push({
          ...file,
          id: latestFilesStatus[file.uniqueId]?.fileId ?? file.id,
          downloadStatus:
            latestFilesStatus[file.uniqueId]?.downloadStatus ??
            file.downloadStatus,
          localPath:
            latestFilesStatus[file.uniqueId]?.localPath ?? file.localPath,
          completionDate:
            latestFilesStatus[file.uniqueId]?.completionDate ??
            file.completionDate,
          downloadedSize:
            latestFilesStatus[file.uniqueId]?.downloadedSize ??
            file.downloadedSize,
          transferStatus:
            latestFilesStatus[file.uniqueId]?.transferStatus ??
            file.transferStatus,
          thumbnailFile:
            latestFilesStatus[file.uniqueId]?.thumbnailFile ??
            file.thumbnailFile,
        });
      });
    });
    files.forEach((file, index) => {
      file.prev = files[index - 1];
      file.next = files[index + 1];
    });
    return files;
  }, [pages, latestFilesStatus]);

  const hasMore = useMemo(() => {
    if (!pages || pages.length === 0) return true;

    const fetchedCount = pages.reduce((acc, d) => acc + d.files.length, 0);
    const lastPage = pages[pages.length - 1];
    let hasMore = false;
    if (lastPage) {
      const count = lastPage.count;
      hasMore = count > fetchedCount && lastPage.nextFromMessageId !== 0;
    }
    return hasMore;
  }, [pages]);

  const handleLoadMore = async () => {
    if (isLoading || isValidating || !hasMore || error) return;
    await setSize(size + 1);
  };

  const handleFilterChange = async (newFilters: FileFilter) => {
    // Deep comparison for arrays (downloadStatuses, tags)
    const filtersEqual = Object.keys({ ...filters, ...newFilters }).every((key) => {
      const newValue = newFilters[key as keyof FileFilter];
      const oldValue = filters[key as keyof FileFilter];
      
      // Handle array comparison
      if (Array.isArray(newValue) && Array.isArray(oldValue)) {
        if (newValue.length !== oldValue.length) return false;
        return newValue.every((val, idx) => val === oldValue[idx]);
      }
      
      // If one is array and other is not, they're different
      if (Array.isArray(newValue) !== Array.isArray(oldValue)) {
        return false;
      }
      
      // Handle undefined comparison (both undefined means equal)
      if (newValue === undefined && oldValue === undefined) return true;
      
      // Handle empty array comparison (both empty arrays mean equal)
      if (Array.isArray(newValue) && Array.isArray(oldValue)) {
        if (newValue.length === 0 && oldValue.length === 0) return true;
      }
      
      return newValue === oldValue;
    });
    
    if (filtersEqual) {
      return;
    }
    
    // Mark that we're changing filters
    isFilterChangingRef.current = true;
    
    // Update filters - useEffect will handle the refetch after state updates
    setFilters(newFilters);
  };

  const updateField = async (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => {
    await mutate((pages) => {
      if (!pages) return [];

      return pages.map((page) => {
        const newFiles = page.files.map((file) =>
          file.uniqueId === uniqueId ? { ...file, ...patch } : file,
        );
        return {
          ...page,
          files: newFiles,
        };
      });
    }, false);
  };

  return {
    size,
    files,
    filters,
    isLoading: debounceLoading,
    updateField,
    handleFilterChange,
    clearFilters,
    handleLoadMore,
    hasMore,
  };
}
