import * as React from "react";
import { Check, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Badge } from "@/components/ui/badge";
import type { DownloadStatus } from "@/lib/types";
import { DOWNLOAD_STATUS } from "@/components/file-status";
import { Label } from "@/components/ui/label";

interface FileStatusMultiselectProps {
  selectedStatuses: DownloadStatus[];
  onChange: (statuses: DownloadStatus[]) => void;
}

const allStatuses: DownloadStatus[] = [
  "idle",
  "downloading",
  "paused",
  "completed",
  "processed",
  "imported",
  "error",
];

export default function FileStatusMultiselect({
  selectedStatuses,
  onChange,
}: FileStatusMultiselectProps) {
  const [open, setOpen] = React.useState(false);

  const toggleStatus = (status: DownloadStatus) => {
    const newStatuses = selectedStatuses.includes(status)
      ? selectedStatuses.filter((s) => s !== status)
      : [...selectedStatuses, status];
    onChange(newStatuses);
  };

  const selectAll = () => {
    onChange([...allStatuses]);
  };

  const clearAll = () => {
    onChange([]);
  };

  const displayText = React.useMemo(() => {
    if (selectedStatuses.length === 0) {
      return "No statuses selected";
    }
    if (selectedStatuses.length === allStatuses.length) {
      return "All statuses";
    }
    if (selectedStatuses.length === 1) {
      const firstStatus = selectedStatuses[0];
      if (!firstStatus) return "1 status";
      const statusConfig = DOWNLOAD_STATUS[firstStatus];
      return statusConfig ? statusConfig.text : firstStatus;
    }
    return `${selectedStatuses.length} statuses`;
  }, [selectedStatuses]);

  return (
    <div className="space-y-2">
      <Label>Download Status</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className="w-full justify-between"
          >
            <span className="truncate">{displayText}</span>
            <div className="ml-2 flex gap-1">
              {selectedStatuses.slice(0, 3).map((status) => {
                const statusConfig = DOWNLOAD_STATUS[status];
                if (!statusConfig) return null;
                const StatusIcon = statusConfig.icon;
                return (
                  <StatusIcon
                    key={status}
                    className={cn("h-4 w-4", statusConfig.className)}
                  />
                );
              })}
              {selectedStatuses.length > 3 && (
                <span className="text-xs text-muted-foreground">
                  +{selectedStatuses.length - 3}
                </span>
              )}
            </div>
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[300px] p-0" align="start">
          <Command>
            <CommandInput placeholder="Search status..." />
            <CommandList>
              <CommandEmpty>No status found.</CommandEmpty>
              <CommandGroup>
                <div className="flex justify-between border-b p-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => {
                      e.preventDefault();
                      selectAll();
                    }}
                  >
                    Select All
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => {
                      e.preventDefault();
                      clearAll();
                    }}
                  >
                    Clear All
                  </Button>
                </div>
                {allStatuses.map((status) => {
                  const isSelected = selectedStatuses.includes(status);
                  const statusConfig = DOWNLOAD_STATUS[status];
                  if (!statusConfig) return null;
                  const StatusIcon = statusConfig.icon;
                  return (
                    <CommandItem
                      key={status}
                      value={status}
                      onSelect={() => toggleStatus(status)}
                    >
                      <div
                        className={cn(
                          "mr-2 flex h-4 w-4 items-center justify-center rounded-sm border border-primary",
                          isSelected
                            ? "bg-primary text-primary-foreground"
                            : "opacity-50 [&_svg]:invisible",
                        )}
                      >
                        <Check className="h-4 w-4" />
                      </div>
                      <StatusIcon
                        className={cn("mr-2 h-4 w-4", statusConfig.className)}
                      />
                      <span>{statusConfig.text}</span>
                    </CommandItem>
                  );
                })}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
      {selectedStatuses.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {selectedStatuses.map((status) => {
            const statusConfig = DOWNLOAD_STATUS[status];
            if (!statusConfig) return null;
            const StatusIcon = statusConfig.icon;
            return (
              <Badge
                key={status}
                variant="secondary"
                className={cn("text-xs", statusConfig.className)}
              >
                <StatusIcon className="mr-1 h-3 w-3" />
                {statusConfig.text}
                <button
                  className="ml-1 rounded-full outline-none ring-offset-background focus:ring-2 focus:ring-ring focus:ring-offset-2"
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      toggleStatus(status);
                    }
                  }}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                  }}
                  onClick={(e) => {
                    e.preventDefault();
                    toggleStatus(status);
                  }}
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            );
          })}
        </div>
      )}
    </div>
  );
}
