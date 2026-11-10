// TOMATE — configuration publique du backend.
// La clé « publishable » est destinée au client : elle ne donne accès qu'à ce que les
// politiques RLS autorisent. Aucune clé secrète ne doit jamais être ajoutée ici.

export const SUPABASE_URL = 'https://omsupwmlncuapikbjumg.supabase.co';
export const SUPABASE_KEY = 'sb_publishable_IOga6ANcbYMivphwt4mR7Q_VziRyrIj';

export const APP_VERSION = '0.2.5-web';
export const LOCAL_PREFIX = 'local-';
export const MAX_POST_LENGTH = 2000;
export const MAX_COMMENT_LENGTH = 1000;
export const MAX_MESSAGE_LENGTH = 4000;
export const MAX_STORY_LENGTH = 300;
