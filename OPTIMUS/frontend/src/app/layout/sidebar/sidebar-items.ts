import { RouteInfo } from './sidebar.metadata';

export const ROUTES: RouteInfo[] = [
  {
    path: '',
    title: 'NEO - AML PIPELINE',
    moduleName: '',
    iconType: '',
    icon: '',
    class: '',
    groupTitle: true,
    badge: '',
    badgeClass: '',
    role: ['All'],
    submenu: [],
  },

  {
    path: '/graph',
    title: 'Graph Workbench',
    moduleName: 'graph',
    iconType: 'feather',
    icon: 'share',
    class: '',
    groupTitle: false,
    badge: '',
    badgeClass: '',
    role: ['All'],
    submenu: [],
  },
];
