import { type TelegramFile } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import React from "react";
import { cn } from "@/lib/utils";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { AnimatePresence, motion } from "framer-motion";
import {
  CheckCircle2,
  CircleHelp,
  Clock,
  Download,
  FolderSync,
  Music,
  Package,
  Pause,
  XCircle,
} from "lucide-react";
import useIsMobile from "@/hooks/use-is-mobile";

export const DOWNLOAD_STATUS = {
  idle: {
    icon: Clock,
    className: "bg-gray-100 text-gray-600",
    text: "Idle",
  },
  downloading: {
    icon: Download,
    className: "bg-blue-100 text-blue-600",
    text: "Downloading",
  },
  paused: {
    icon: Pause,
    className: "bg-yellow-100 text-yellow-600",
    text: "Paused",
  },
  completed: {
    icon: CheckCircle2,
    className: "bg-green-100 text-green-600",
    text: "Completed",
  },
  processed: {
    icon: Package,
    className: "bg-purple-100 text-purple-600",
    text: "Processed",
  },
  imported: {
    icon: Music,
    className: "bg-indigo-100 text-indigo-600",
    text: "Imported",
  },
  error: {
    icon: XCircle,
    className: "bg-red-100 text-red-600",
    text: "Error",
  },
};

const DOWNLOAD_STATUS_FALLBACK = {
  icon: CircleHelp,
  className: "bg-gray-100 text-gray-500",
  text: "Unknown",
};

export function getDownloadStatusConfig(status: string) {
  return DOWNLOAD_STATUS[status as keyof typeof DOWNLOAD_STATUS] ?? DOWNLOAD_STATUS_FALLBACK;
}

export const TRANSFER_STATUS = {
  idle: {
    icon: Clock,
    className: "bg-gray-100 text-gray-600",
    text: "Idle",
  },
  transferring: {
    icon: FolderSync,
    className: "bg-blue-100 text-blue-600",
    text: "Transferring",
  },
  completed: {
    icon: CheckCircle2,
    className: "bg-green-100 text-green-600",
    text: "Transferred",
  },
  error: {
    icon: XCircle,
    className: "bg-red-100 text-red-600",
    text: "Transfer Error",
  },
};

export default function FileStatus({
  file,
  className,
}: {
  file: TelegramFile;
  className?: string;
}) {
  const badgeVariants = {
    initial: { opacity: 0, scale: 0.9 },
    animate: {
      opacity: 1,
      scale: 1,
      transition: { type: "spring", stiffness: 300 },
    },
    exit: { opacity: 0, scale: 0.9, transition: { duration: 0.2 } },
  };
  const isMobile = useIsMobile();

  return (
    <div
      className={cn("flex items-center justify-center space-x-2", className)}
    >
      <AnimatePresence>
        {file.transferStatus === "idle" && (
          <motion.div
            key="download-status"
            variants={badgeVariants}
            initial="initial"
            animate="animate"
            exit="exit"
          >
            <TooltipWrapper content="Download Status">
              <Badge
                className={cn(
                  "h-6 text-xs hover:bg-gray-200",
                  getDownloadStatusConfig(file.downloadStatus).className,
                  isMobile && "shadow-none",
                )}
              >
                {getDownloadStatusConfig(file.downloadStatus).text}
              </Badge>
            </TooltipWrapper>
          </motion.div>
        )}
        {file.downloadStatus === "completed" &&
          file.transferStatus &&
          file.transferStatus !== "idle" && (
            <motion.div
              key="transfer-status"
              variants={badgeVariants}
              initial="initial"
              animate="animate"
              exit="exit"
            >
              <TooltipWrapper content="Transfer Status">
                <Badge
                  className={cn(
                    "h-6 text-xs hover:bg-gray-200",
                    TRANSFER_STATUS[file.transferStatus].className,
                  )}
                >
                  {TRANSFER_STATUS[file.transferStatus].text}
                </Badge>
              </TooltipWrapper>
            </motion.div>
          )}
      </AnimatePresence>
    </div>
  );
}
