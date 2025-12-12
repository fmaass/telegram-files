import { type TelegramObject } from "@/lib/websocket-types";

export type TelegramAccount = {
  id: string;
  name: string;
  phoneNumber: string;
  avatar?: string;
  status: "active" | "inactive";
  lastAuthorizationState?: TelegramObject;
  proxy?: string;
  rootPath: string;
};

export type TelegramChat = {
  id: string;
  name: string;
  type: "private" | "group" | "channel";
  avatar?: string;
  unreadCount?: number;
  lastMessage?: string;
  lastMessageTime?: string;
  auto?: Auto & {
    state: number;
  };
};

export type FileType = "media" | "photo" | "video" | "audio" | "file";
export type DownloadStatus =
  | "idle"
  | "downloading"
  | "paused"
  | "completed"
  | "processed"
  | "error";

export type TransferStatus = "idle" | "transferring" | "completed" | "error";

export type TelegramFile = {
  id: number;
  telegramId: number;
  uniqueId: string;
  messageId: number;
  chatId: number;
  fileName: string;
  type: FileType;
  mimeType?: string;
  size: number;
  downloadedSize: number;
  thumbnail?: string;
  thumbnailFile?: Thumbnail;
  downloadStatus: DownloadStatus;
  date: number;
  formatDate: string;
  caption: string;
  localPath: string;
  hasSensitiveContent: boolean;
  startDate: number;
  completionDate: number;
  originalDeleted: boolean;
  transferStatus?: TransferStatus;
  extra?: PhotoExtra | VideoExtra;
  tags?: string;
  loaded: boolean;
  threadChatId: number;
  messageThreadId: number;
  hasReply?: boolean;
  reactionCount: number;

  prev?: TelegramFile;
  next?: TelegramFile;
};

export type PhotoExtra = {
  width: number;
  height: number;
  type: string;
};

export type VideoExtra = {
  width: number;
  height: number;
  duration: number;
  mimeType: string;
};

export type Thumbnail = {
  uniqueId: string;
  mimeType: string;
  extra: {
    width: number;
    height: number;
  };
};

export type TDFile = {
  id: number;
  size: number;
  expectedSize: number;
  local?: {
    path: string;
    canBeDownloaded: boolean;
    canBeDeleted: boolean;
    isDownloadingActive: boolean;
    isDownloadingCompleted: boolean;
    downloadOffset: number;
    downloadedPrefixSize: number;
    downloadedSize: number;
  };
  remote: {
    id: number;
    uniqueId: string;
    isUploadingActive: boolean;
    isUploadingCompleted: boolean;
    uploadedSize: number;
  };
};

export type SortFields = "date" | "completion_date" | "size" | "reaction_count";

export type FileFilter = {
  search: string;
  type: FileType | "all";
  downloadStatus?: DownloadStatus;
  transferStatus?: TransferStatus;
  offline: boolean;
  tags: string[];
  dateType?: "sent" | "downloaded";
  dateRange?: [string, string];
  sizeRange?: [number, number];
  sizeUnit?: "KB" | "MB" | "GB";
  sort?: SortFields;
  order?: "asc" | "desc";
};

export type TelegramApiResult = {
  code: string;
};

export const SettingKeys = [
  "uniqueOnly",
  "imageLoadSize",
  "alwaysHide",
  "showSensitiveContent",
  "autoDownloadLimit",
  "autoDownloadTimeLimited",
  "proxys",
  "avgSpeedInterval",
  "tags",
  "showAlbumArtForMovedFiles",
] as const;

export type SettingKey = (typeof SettingKeys)[number];

export type Settings = Record<SettingKey, string>;

export type Proxy = {
  id?: string;
  name: string;
  server: string;
  port: number;
  username: string;
  password: string;
  secret: string;
  type: "http" | "socks5" | "mtproto";
  isEnabled?: boolean;
};

export type Auto = {
  preload: {
    enabled: boolean;
  };
  download: {
    enabled: boolean;
    rule: AutoDownloadRule;
  };
  transfer: {
    enabled: boolean;
    rule: AutoTransferRule;
  };
};

export const TransferPolices = ["GROUP_BY_CHAT", "GROUP_BY_TYPE"] as const;
export type TransferPolicy = (typeof TransferPolices)[number];
export const DuplicationPolicies = [
  "OVERWRITE",
  "RENAME",
  "SKIP",
  "HASH",
] as const;
export type DuplicationPolicy = (typeof DuplicationPolicies)[number];

export type AutoTransferRule = {
  transferHistory: boolean;
  destination: string;
  transferPolicy: TransferPolicy;
  duplicationPolicy: DuplicationPolicy;
};

export type AutoDownloadRule = {
  query: string;
  fileTypes: Array<Exclude<FileType, "media">>;
  downloadHistory: boolean;
  downloadCommentFiles: boolean;
  /**
   * Optional ISO-8601 timestamp (UTC) that bounds history backfill.
   * If present, only messages sent on/after this date are considered.
   * If null/undefined, all history is considered (when downloadHistory is true).
   */
  historySince?: string | null;
  /**
   * If true, download files from oldest to newest.
   * If false (default), download from newest to oldest.
   */
  downloadOldestFirst?: boolean;
};
