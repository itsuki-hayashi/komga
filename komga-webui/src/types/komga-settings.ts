export interface SettingsDto {
  deleteEmptyCollections: boolean,
  deleteEmptyReadLists: boolean,
  rememberMeDurationDays: number,
  thumbnailSize: ThumbnailSizeDto,
  taskPoolSize: number,
  serverPort: SettingMultiSource<number>,
  serverContextPath: SettingMultiSource<string>,
  koboProxy: boolean,
}

export interface SettingMultiSource<T> {
  configurationSource?: T,
  databaseSource?: T,
  effectiveValue?: T,
}

export interface SettingsUpdateDto {
  deleteEmptyCollections?: boolean,
  deleteEmptyReadLists?: boolean,
  rememberMeDurationDays?: number,
  renewRememberMeKey?: boolean,
  thumbnailSize?: ThumbnailSizeDto,
  taskPoolSize?: number,
  serverPort?: number,
  serverContextPath?: string,
  koboProxy?: boolean,
}

export enum ThumbnailSizeDto {
  DEFAULT = 'DEFAULT',
  MEDIUM = 'MEDIUM',
  LARGE = 'LARGE',
  XLARGE = 'XLARGE',
}
