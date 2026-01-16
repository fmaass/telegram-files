"use client";

import { Badge } from "@/components/ui/badge";
import { TooltipWrapper } from "./ui/tooltip";
import { CheckCircle, Clock, Download, AlertTriangle } from "lucide-react";
import useSWR from "swr";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { useTelegramAccount } from "@/hooks/use-telegram-account";

interface ChatDownloadStats {
  total: number;
  downloading: number;
  paused: number;
  completed: number;
  error: number;
  idle: number;
}

export function ChatDownloadBadge() {
  const { accountId } = useTelegramAccount();
  const { chatId } = useTelegramChat();

  const { data: stats } = useSWR<ChatDownloadStats>(
    accountId && chatId
      ? `/telegram/${accountId}/chat/${chatId}/statistics`
      : null,
    {
      refreshInterval: 30000, // Refresh every 30 seconds (reduced from 5s to avoid DB load)
      dedupingInterval: 30000, // Match refresh interval to prevent redundant requests
      revalidateOnFocus: false, // Don't revalidate when window regains focus
    },
  );

  if (!stats || stats.total === 0) {
    return null;
  }

  const pendingCount = (stats.idle ?? 0) + (stats.downloading ?? 0);
  const isComplete = pendingCount === 0;

  return (
    <TooltipWrapper
      content={
        <div className="space-y-1 text-xs">
          <div className="font-semibold">Download Progress</div>
          <div className="flex justify-between gap-4">
            <span>Total:</span>
            <span>{stats.total}</span>
          </div>
          <div className="flex justify-between gap-4">
            <span>✓ Completed:</span>
            <span>{stats.completed}</span>
          </div>
          <div className="flex justify-between gap-4">
            <span>↓ Downloading:</span>
            <span>{stats.downloading}</span>
          </div>
          <div className="flex justify-between gap-4">
            <span>⏸ Paused:</span>
            <span>{stats.paused}</span>
          </div>
          <div className="flex justify-between gap-4">
            <span>○ Pending:</span>
            <span>{stats.idle}</span>
          </div>
          {stats.error > 0 && (
            <div className="flex justify-between gap-4 text-red-400">
              <span>⚠ Errors:</span>
              <span>{stats.error}</span>
            </div>
          )}
        </div>
      }
    >
      <Badge
        variant={isComplete ? "default" : "secondary"}
        className="flex items-center gap-1.5"
      >
        {isComplete ? (
          <>
            <CheckCircle className="h-3.5 w-3.5" />
            <span className="text-xs">
              {stats.completed}/{stats.total} complete
            </span>
          </>
        ) : (
          <>
            {stats.downloading > 0 ? (
              <Download className="h-3.5 w-3.5 animate-pulse" />
            ) : (
              <Clock className="h-3.5 w-3.5" />
            )}
            <span className="text-xs">
              {stats.completed}/{stats.total}
              {pendingCount > 0 && (
                <span className="ml-1 opacity-70">
                  ({pendingCount} left)
                </span>
              )}
            </span>
          </>
        )}
        {stats.error > 0 && (
          <AlertTriangle className="ml-0.5 h-3.5 w-3.5 text-yellow-500" />
        )}
      </Badge>
    </TooltipWrapper>
  );
}

